import request from '@/api/request'

const unwrap = (response) => response?.data ?? response ?? {}

const pathPart = (value) => encodeURIComponent(value)

export function listDatabaseCatalogs() {
  return request({ url: '/ops/api/database/catalogs', method: 'get' }).then(unwrap)
}

export function listDatabaseTables(database) {
  return request({
    url: `/ops/api/database/${pathPart(database)}/tables`,
    method: 'get'
  }).then(unwrap)
}

export function getTableMetadata(database, table) {
  return request({
    url: `/ops/api/database/${pathPart(database)}/tables/${pathPart(table)}/metadata`,
    method: 'get'
  }).then(unwrap)
}

export function listTableRows(database, table, params = {}) {
  return request({
    url: `/ops/api/database/${pathPart(database)}/tables/${pathPart(table)}/rows`,
    method: 'get',
    params
  }).then(unwrap)
}

export function createTable(database, data) {
  return request({
    url: `/ops/api/database/${pathPart(database)}/tables`,
    method: 'post',
    data
  }).then(unwrap)
}

export function addTableColumn(database, table, data) {
  return request({
    url: `/ops/api/database/${pathPart(database)}/tables/${pathPart(table)}/columns`,
    method: 'post',
    data
  }).then(unwrap)
}

export function createTableRow(database, table, values) {
  return request({
    url: `/ops/api/database/${pathPart(database)}/tables/${pathPart(table)}/rows`,
    method: 'post',
    data: { values }
  }).then(unwrap)
}

export function updateTableRow(database, table, primaryKey, values, expectedRowVersion) {
  return request({
    url: `/ops/api/database/${pathPart(database)}/tables/${pathPart(table)}/rows/${pathPart(primaryKey)}`,
    method: 'patch',
    data: { values, expectedRowVersion }
  }).then(unwrap)
}

export function deleteTableRow(database, table, primaryKey) {
  return request({
    url: `/ops/api/database/${pathPart(database)}/tables/${pathPart(table)}/rows/${pathPart(primaryKey)}`,
    method: 'delete'
  }).then(unwrap)
}
