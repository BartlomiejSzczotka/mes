package com.qcadoo.mes.core.data.internal;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import com.qcadoo.mes.core.data.api.DataDefinitionService;
import com.qcadoo.mes.core.data.definition.ColumnDefinition;
import com.qcadoo.mes.core.data.definition.DataDefinition;
import com.qcadoo.mes.core.data.definition.FieldDefinition;
import com.qcadoo.mes.core.data.definition.FieldTypes;
import com.qcadoo.mes.core.data.definition.GridDefinition;

@Service
public class DataDefinitionServiceImpl implements DataDefinitionService {

    @Override
    public void save(DataDefinition dataDefinition) {
        throw new UnsupportedOperationException("implement me");
    }

    @Override
    public DataDefinition get(String entityName) {
        DataDefinition dataDefinition = new DataDefinition("products.Product");
        GridDefinition gridDefinition = new GridDefinition("Products");

        FieldDefinition fieldNumber = createStringFieldDefinition("number");
        FieldDefinition fieldType = createStringFieldDefinition("type");
        FieldDefinition fieldTypeOfMaterial = createStringFieldDefinition("typeOfMaterial");
        FieldDefinition fieldEan = createStringFieldDefinition("ean");
        FieldDefinition fieldCategory = createStringFieldDefinition("category");
        FieldDefinition fieldUnit = createStringFieldDefinition("unit");

        dataDefinition.setFullyQualifiedClassName("com.qcadoo.mes.core.data.beans.Product");
        dataDefinition.setGrids(Arrays.asList(new GridDefinition[] { gridDefinition }));
        dataDefinition.setFields(Arrays.asList(new FieldDefinition[] { fieldNumber, fieldType, fieldTypeOfMaterial, fieldEan,
                fieldCategory, fieldUnit }));

        ColumnDefinition columnNumber = createColumnDefinition("number", fieldNumber);
        ColumnDefinition columnType = createColumnDefinition("type", fieldType);
        ColumnDefinition columnTypeOfMaterial = createColumnDefinition("typeOfMaterial", fieldTypeOfMaterial);
        ColumnDefinition columnEan = createColumnDefinition("ean", fieldEan);
        ColumnDefinition columnCategory = createColumnDefinition("category", fieldCategory);
        ColumnDefinition columnUnit = createColumnDefinition("unit", fieldUnit);

        gridDefinition.setColumns(Arrays.asList(new ColumnDefinition[] { columnNumber, columnType, columnTypeOfMaterial,
                columnEan, columnCategory, columnUnit }));

        return dataDefinition;
    }

    private ColumnDefinition createColumnDefinition(String name, FieldDefinition field) {
        ColumnDefinition columnDefinition = new ColumnDefinition();
        columnDefinition.setName(name);
        columnDefinition.setFields(Arrays.asList(new FieldDefinition[] { field }));
        return columnDefinition;
    }

    private FieldDefinition createStringFieldDefinition(String name) {
        FieldDefinition fieldDefinition = new FieldDefinition();
        fieldDefinition.setName(name);
        fieldDefinition.setType(FieldTypes.stringType());
        return fieldDefinition;
    }

    @Override
    public void delete(String entityName) {
        throw new UnsupportedOperationException("implement me");
    }

    @Override
    public List<DataDefinition> list() {
        throw new UnsupportedOperationException("implement me");
    }

}
