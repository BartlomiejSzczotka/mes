package com.qcadoo.mes.core.data.internal.definition;

import com.qcadoo.mes.core.data.definition.FieldType;

public final class IntFieldType implements FieldType {

    @Override
    public boolean isSearchable() {
        return true;
    }

    @Override
    public boolean isOrderable() {
        return true;
    }

    @Override
    public boolean isAggregable() {
        return true;
    }

    @Override
    public boolean isValidType(Object value) {
        return value instanceof Integer;
    }

}
