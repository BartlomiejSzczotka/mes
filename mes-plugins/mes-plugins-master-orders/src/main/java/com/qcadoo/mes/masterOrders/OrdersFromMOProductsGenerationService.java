package com.qcadoo.mes.masterOrders;

import com.google.common.collect.Lists;
import com.qcadoo.localization.api.TranslationService;
import com.qcadoo.mes.basic.ParameterService;
import com.qcadoo.mes.basic.ShiftsService;
import com.qcadoo.mes.basic.constants.BasicConstants;
import com.qcadoo.mes.basic.constants.ProductFields;
import com.qcadoo.mes.lineChangeoverNorms.ChangeoverNormsService;
import com.qcadoo.mes.lineChangeoverNorms.constants.LineChangeoverNormsFields;
import com.qcadoo.mes.lineChangeoverNormsForOrders.LineChangeoverNormsForOrdersService;
import com.qcadoo.mes.masterOrders.constants.MasterOrderFields;
import com.qcadoo.mes.masterOrders.constants.MasterOrderProductFields;
import com.qcadoo.mes.masterOrders.constants.OrderFieldsMO;
import com.qcadoo.mes.orders.OrderService;
import com.qcadoo.mes.orders.TechnologyServiceO;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.constants.OrderType;
import com.qcadoo.mes.orders.constants.OrdersConstants;
import com.qcadoo.mes.orders.constants.ParameterFieldsO;
import com.qcadoo.mes.orders.states.constants.OrderState;
import com.qcadoo.mes.orders.states.constants.OrderStateStringValues;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.exception.EntityRuntimeException;
import com.qcadoo.model.api.search.SearchOrders;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.view.api.utils.NumberGeneratorService;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.qcadoo.mes.orders.constants.OrderFields.PRODUCTION_LINE;
import static com.qcadoo.model.api.BigDecimalUtils.convertNullToZero;

@Service
public class OrdersFromMOProductsGenerationService {

    private static final List<String> L_TECHNOLOGY_FIELD_NAMES = Lists.newArrayList("registerQuantityInProduct",
            "registerQuantityOutProduct", "registerProductionTime", "registerPiecework", "justOne", "allowToClose",
            "autoCloseOrder", "typeOfProductionRecording");

    @Autowired
    private TechnologyServiceO technologyServiceO;

    @Autowired
    private ParameterService parameterService;

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private NumberGeneratorService numberGeneratorService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private TranslationService translationService;

    @Autowired
    private ChangeoverNormsService changeoverNormsService;

    @Autowired
    private LineChangeoverNormsForOrdersService lineChangeoverNormsForOrdersService;

    @Autowired
    private ShiftsService shiftsService;

    public GenerationOrderResult generateOrders(List<Entity> masterOrderProducts, boolean generatePPS) {
        GenerationOrderResult result = new GenerationOrderResult(translationService);
        boolean automaticPps = parameterService.getParameter().getBooleanField("ppsIsAutomatic");
        masterOrderProducts.forEach(masterOrderProduct -> {
            Optional<Entity> dtoEntity = Optional.ofNullable(masterOrderProduct.getDataDefinition().getMasterModelEntity(
                    masterOrderProduct.getId()));
            if (dtoEntity.isPresent()) {
                generateOrder(generatePPS, automaticPps, result, dtoEntity.get());
            } else {
                generateOrder(generatePPS, automaticPps, result, masterOrderProduct);
            }
        });

        return result;

    }

    private void generateOrder(boolean generatePPS, boolean automaticPps, GenerationOrderResult result, Entity masterOrderProduct) {
        Entity order = createOrder(masterOrderProduct);
        order = getOrderDD().save(order);
        if (!order.isValid()) {
            MasterOrderProductErrorContainer productErrorContainer = new MasterOrderProductErrorContainer();
            productErrorContainer.setProduct(masterOrderProduct.getBelongsToField(MasterOrderProductFields.PRODUCT)
                    .getStringField(ProductFields.NUMBER));
            productErrorContainer.setMasterOrder(masterOrderProduct.getBelongsToField(MasterOrderProductFields.MASTER_ORDER)
                    .getStringField(MasterOrderFields.NUMBER));
            productErrorContainer.setQuantity(order.getDecimalField(OrderFields.PLANNED_QUANTITY));
            productErrorContainer.setErrorMessages(order.getGlobalErrors());
            result.addNotGeneratedProductError(productErrorContainer);
        } else {
            result.addGeneratedOrderNumber(order.getStringField(OrderFields.NUMBER));
        }

        if (order.isValid() && generatePPS && automaticPps) {
            try {
                tryGeneratePPS(order);
            } catch (Exception ex) {
                result.addOrderWithoutPps(order.getStringField(OrderFields.NUMBER));
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void tryGeneratePPS(final Entity order) {
        Date startDate = findStartDate(order);
        generateEmptyPpsForOrder(order);
        order.setField("generatePPS", true);
        order.setField(OrderFields.START_DATE, startDate);
        order.setField(OrderFields.FINISH_DATE, new DateTime(order.getDateField(OrderFields.START_DATE)).plusDays(1).toDate());
        Entity storedOrder = order.getDataDefinition().save(order);
        if (!storedOrder.isValid()) {
            throw new EntityRuntimeException(storedOrder);
        }
    }

    private void generateEmptyPpsForOrder(Entity order) {
        Entity productionPerShift = dataDefinitionService.get("productionPerShift", "productionPerShift").find()
                .add(SearchRestrictions.belongsTo("order", order)).setMaxResults(1).uniqueResult();
        if (productionPerShift != null) {
            return;
        }
        boolean shouldBeCorrected = OrderState.of(order).compareTo(OrderState.PENDING) != 0;
        productionPerShift = dataDefinitionService.get("productionPerShift", "productionPerShift").create();
        productionPerShift.setField("order", order);
        if (shouldBeCorrected) {
            productionPerShift.setField("plannedProgressType", "02corrected");
        } else {
            productionPerShift.setField("plannedProgressType", "01planned");
        }
        productionPerShift.getDataDefinition().save(productionPerShift);
    }

    private Date findStartDate(final Entity order) {
        if (Objects.nonNull(order.getDateField(OrderFields.START_DATE))) {
            return order.getDateField(OrderFields.START_DATE);
        }

        Optional<Entity> previousOrder = findPreviousOrder(order);
        if (previousOrder.isPresent()) {
            Integer changeoverDurationInMillis = getChangeoverDurationInMillis(previousOrder.get(), order);
            List<Entity> shifts = getAllShifts();
            Optional<DateTime> maybeDate = shiftsService.getNearestWorkingDate(
                    new DateTime(previousOrder.get().getDateField(OrderFields.FINISH_DATE)), shifts);
            if (maybeDate.isPresent()) {
                return calculateOrderStartDate(maybeDate.get().toDate(), changeoverDurationInMillis);
            }
        }

        return DateTime.now().toDate();
    }

    private Date calculateOrderStartDate(Date finishDate, Integer changeoverDurationInMillis) {
        DateTime finishDateTime = new DateTime(finishDate);
        finishDateTime = finishDateTime.plusMillis(changeoverDurationInMillis);
        return finishDateTime.toDate();
    }

    public Optional<Entity> findPreviousOrder(final Entity order) {
        Entity productionLine = order.getBelongsToField(OrderFields.PRODUCTION_LINE);
        Entity nextOrder = dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_ORDER).find()
                .add(SearchRestrictions.belongsTo(OrderFields.PRODUCTION_LINE, productionLine))
                .add(SearchRestrictions.isNotNull(OrderFields.START_DATE)).addOrder(SearchOrders.desc(OrderFields.START_DATE))
                .setMaxResults(1).uniqueResult();
        return Optional.ofNullable(nextOrder);
    }

    public Integer getChangeoverDurationInMillis(Entity previousOrder, final Entity nextOrder) {
        Entity fromTechnology = previousOrder.getBelongsToField(OrderFields.TECHNOLOGY_PROTOTYPE);
        Entity toTechnology = nextOrder.getBelongsToField(OrderFields.TECHNOLOGY_PROTOTYPE);
        Entity productionLine = nextOrder.getBelongsToField(PRODUCTION_LINE);
        Entity changeover = changeoverNormsService.getMatchingChangeoverNorms(fromTechnology, toTechnology, productionLine);
        if (changeover != null) {
            Integer duration = changeover.getIntegerField(LineChangeoverNormsFields.DURATION);
            if (duration == null) {
                return 0;
            }
            return duration * 1000;
        }
        return 0;
    }

    public Entity createOrder(final Entity masterOrderProduct) {
        Entity parameter = parameterService.getParameter();
        Entity masterOrder = masterOrderProduct.getBelongsToField(MasterOrderProductFields.MASTER_ORDER);
        Entity product = masterOrderProduct.getBelongsToField(MasterOrderProductFields.PRODUCT);
        Entity technology = getTechnology(masterOrderProduct);
        Date masterOrderDeadline = masterOrder.getDateField(MasterOrderFields.DEADLINE);
        Date masterOrderStartDate = masterOrder.getDateField(MasterOrderFields.START_DATE);
        Date masterOrderFinishDate = masterOrder.getDateField(MasterOrderFields.FINISH_DATE);

        Entity order = getOrderDD().create();
        order.setField(OrderFields.NUMBER, generateOrderNumber(parameter, masterOrder));
        order.setField(OrderFields.NAME, generateOrderName(product, technology));
        order.setField(OrderFields.COMPANY, masterOrder.getBelongsToField(MasterOrderFields.COMPANY));
        order.setField(OrderFields.ADDRESS, masterOrder.getBelongsToField(MasterOrderFields.ADDRESS));
        order.setField(OrderFields.PRODUCT, product);
        order.setField(OrderFields.TECHNOLOGY_PROTOTYPE, technology);
        order.setField(OrderFields.PRODUCTION_LINE, getProductionLine(technology));
        order.setField(OrderFields.DATE_FROM, masterOrderStartDate);
        order.setField(OrderFields.DATE_TO, masterOrderFinishDate);
        order.setField(OrderFields.DEADLINE, masterOrderDeadline);
        order.setField(OrderFields.EXTERNAL_SYNCHRONIZED, true);
        order.setField("isSubcontracted", false);
        order.setField(OrderFields.STATE, OrderStateStringValues.PENDING);
        order.setField(OrderFieldsMO.MASTER_ORDER, masterOrder);
        order.setField(OrderFields.ORDER_TYPE, OrderType.WITH_PATTERN_TECHNOLOGY.getStringValue());
        order.setField(OrderFields.PLANNED_QUANTITY, getPlannedQuantityForOrder(masterOrderProduct));

        order.setField("ignoreMissingComponents", parameter.getBooleanField("ignoreMissingComponents"));

        boolean fillOrderDescriptionBasedOnTechnology = dataDefinitionService
                .get(BasicConstants.PLUGIN_IDENTIFIER, BasicConstants.MODEL_PARAMETER).find().setMaxResults(1).uniqueResult()
                .getBooleanField(ParameterFieldsO.FILL_ORDER_DESCRIPTION_BASED_ON_TECHNOLOGY_DESCRIPTION);

        String orderDescription = orderService.buildOrderDescription(masterOrder, technology,
                fillOrderDescriptionBasedOnTechnology);
        order.setField(OrderFields.DESCRIPTION, orderDescription);
        return order;
    }

    private BigDecimal getPlannedQuantityForOrder(final Entity masterOrderProduct) {
        BigDecimal masterOrderQuantity, cumulatedOrderQuantity;
        masterOrderQuantity = masterOrderProduct.getDecimalField(MasterOrderProductFields.MASTER_ORDER_QUANTITY);
        cumulatedOrderQuantity = masterOrderProduct.getDecimalField(MasterOrderProductFields.CUMULATED_ORDER_QUANTITY);

        BigDecimal quantity = masterOrderQuantity.subtract(convertNullToZero(cumulatedOrderQuantity));

        return quantity;
    }

    private String generateOrderName(final Entity product, final Entity technology) {
        return orderService.makeDefaultName(product, technology, LocaleContextHolder.getLocale());
    }

    private String generateOrderNumber(final Entity parameter, final Entity masterOrder) {
        return numberGeneratorService.generateNumberWithPrefix(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_ORDER, 3,
                masterOrder.getStringField(MasterOrderFields.NUMBER) + "-");
    }

    public Entity getProductionLine(final Entity technology) {
        Entity productionLine = null;
        if (Objects.nonNull(technology)) {
            productionLine = technology.getBelongsToField("productionLine");
        }
        if (Objects.isNull(productionLine)) {
            productionLine = orderService.getDefaultProductionLine();
        }
        return productionLine;
    }

    private Entity getTechnology(final Entity masterOrderProduct) {
        Entity technology;
        technology = masterOrderProduct.getBelongsToField(MasterOrderProductFields.TECHNOLOGY);
        if (Objects.isNull(technology)) {
            Entity product = masterOrderProduct.getBelongsToField(MasterOrderProductFields.PRODUCT);
            technology = technologyServiceO.getDefaultTechnology(product);
        }

        return technology;
    }

    private void fillPCParametersForOrder(final Entity orderEntity) {
        Entity technology = orderEntity.getBelongsToField(OrderFields.TECHNOLOGY_PROTOTYPE);

        for (String field : L_TECHNOLOGY_FIELD_NAMES) {
            orderEntity.setField(field, getDefaultValueForProductionCounting(technology, field));
        }
    }

    private Object getDefaultValueForProductionCounting(final Entity technology, final String fieldName) {
        return technology.getField(fieldName);
    }

    private DataDefinition getOrderDD() {
        return dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_ORDER);
    }

    private List<Entity> getAllShifts() {
        return getShiftDataDefinition().find().list().getEntities();
    }

    private DataDefinition getShiftDataDefinition() {
        return dataDefinitionService.get(BasicConstants.PLUGIN_IDENTIFIER, BasicConstants.MODEL_SHIFT);
    }
}
