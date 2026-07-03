package com.tonepilot.application.controller.admin;

import com.tonepilot.application.dto.ApiResponse;
import com.tonepilot.infrastructure.admin.data.AdminDataTableService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@CrossOrigin
@RestController
@RequestMapping("/api/admin/data")
public class AdminDataTableController {

    @Autowired
    private AdminDataTableService dataTableService;

    @GetMapping("/tables/tree")
    public ApiResponse<List<AdminDataTableService.TableGroup>> tableTree() {
        return ApiResponse.ok(dataTableService.tableTree());
    }

    @GetMapping("/tables/{tableName}")
    public ApiResponse<AdminDataTableService.TableDefinition> describe(@PathVariable String tableName) {
        return ApiResponse.ok(dataTableService.describe(tableName));
    }

    @GetMapping("/tables/{tableName}/rows")
    public ApiResponse<AdminDataTableService.TableRows> listRows(
            @PathVariable String tableName,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(dataTableService.listRows(tableName, keyword, page, size));
    }

    @PostMapping("/tables/{tableName}/rows")
    public ApiResponse<Map<String, Object>> createRow(@PathVariable String tableName, @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(dataTableService.createRow(tableName, request));
    }

    @PutMapping("/tables/{tableName}/rows/{rowKey}")
    public ApiResponse<Map<String, Object>> updateRow(@PathVariable String tableName, @PathVariable String rowKey, @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(dataTableService.updateRow(tableName, rowKey, request));
    }

    @DeleteMapping("/tables/{tableName}/rows/{rowKey}")
    public ApiResponse<Void> deleteRow(@PathVariable String tableName, @PathVariable String rowKey) {
        dataTableService.deleteRow(tableName, rowKey);
        return ApiResponse.ok();
    }
}
