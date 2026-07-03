package com.tonepilot.repository.admin.data;

import java.util.List;
import java.util.Map;

public interface AdminDataTableRepository {

    long countRows(AdminDataCountQuery query);

    List<Map<String, Object>> listRows(AdminDataListQuery query);

    int insertRow(AdminDataInsertCommand command);

    int updateRow(AdminDataUpdateCommand command);

    int deleteRow(AdminDataDeleteCommand command);

    Map<String, Object> findByPrimaryKey(AdminDataFindQuery query);
}
