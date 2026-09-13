package com.misu.ops.database;

import com.misu.common.domain.AjaxResult;
import com.misu.ops.security.OpsAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.misu.ops.database.DatabaseModels.*;

@RestController
@RequestMapping("/api/database")
public class OpsDatabaseController {
    private final OpsAuthorization authorization;
    private final OpsDatabaseService database;

    public OpsDatabaseController(OpsAuthorization authorization, OpsDatabaseService database) {
        this.authorization = authorization;
        this.database = database;
    }

    @GetMapping("/catalogs")
    public AjaxResult catalogs(HttpServletRequest request) {
        authorization.requireCurrentAdmin();
        return AjaxResult.success(database.catalogs());
    }

    @GetMapping("/{database}/tables")
    public AjaxResult tables(HttpServletRequest request, @PathVariable("database") String databaseName) {
        authorization.requireCurrentAdmin();
        return AjaxResult.success(database.tables(databaseName));
    }

    @GetMapping("/{database}/tables/{table}/metadata")
    public AjaxResult metadata(HttpServletRequest request, @PathVariable("database") String databaseName,
                               @PathVariable("table") String table) {
        authorization.requireCurrentAdmin();
        return AjaxResult.success(database.metadata(databaseName, table));
    }

    @GetMapping("/{database}/tables/{table}/rows")
    public AjaxResult rows(HttpServletRequest request, @PathVariable("database") String databaseName,
                           @PathVariable("table") String table,
                           @RequestParam(name = "page", defaultValue = "1") int page,
                           @RequestParam(name = "pageSize", defaultValue = "50") int pageSize,
                           @RequestParam(name = "sort", required = false) String sort,
                           @RequestParam(name = "order", defaultValue = "asc") String order,
                           @RequestParam(name = "filter", required = false) String filter) {
        authorization.requireCurrentAdmin();
        return AjaxResult.success(database.rows(databaseName, table, page, pageSize, sort, order, filter));
    }

    @PostMapping("/{database}/tables/{table}/rows")
    public AjaxResult create(HttpServletRequest request, @PathVariable("database") String databaseName,
                             @PathVariable("table") String table,
                             @RequestBody CreateRowRequest body) {
        authorization.requireCurrentAdmin();
        return AjaxResult.success(database.create(databaseName, table, body == null ? null : body.values()));
    }

    @PatchMapping("/{database}/tables/{table}/rows/{primaryKey}")
    public AjaxResult update(HttpServletRequest request, @PathVariable("database") String databaseName,
                             @PathVariable("table") String table, @PathVariable("primaryKey") String primaryKey,
                             @RequestBody UpdateRowRequest body) {
        authorization.requireCurrentAdmin();
        return AjaxResult.success(database.update(databaseName, table, primaryKey, body));
    }

    @DeleteMapping("/{database}/tables/{table}/rows/{primaryKey}")
    public AjaxResult delete(HttpServletRequest request, @PathVariable("database") String databaseName,
                             @PathVariable("table") String table, @PathVariable("primaryKey") String primaryKey) {
        authorization.requireCurrentAdmin();
        database.delete(databaseName, table, primaryKey);
        return AjaxResult.success();
    }

    @PostMapping("/{database}/tables")
    public AjaxResult createTable(HttpServletRequest request, @PathVariable("database") String databaseName,
                                  @RequestBody CreateTableRequest body) {
        authorization.requireCurrentAdmin();
        return AjaxResult.success(database.createTable(databaseName, body));
    }

    @PostMapping("/{database}/tables/{table}/columns")
    public AjaxResult addColumn(HttpServletRequest request, @PathVariable("database") String databaseName,
                                @PathVariable("table") String table, @RequestBody AddColumnRequest body) {
        authorization.requireCurrentAdmin();
        return AjaxResult.success(database.addColumn(databaseName, table, body));
    }
}
