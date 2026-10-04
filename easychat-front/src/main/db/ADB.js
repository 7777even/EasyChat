
const fs = require('fs');
const sqlite3 = require('sqlite3').verbose();
const os = require('os');
const NODE_ENV = process.env.NODE_ENV
// 获取当前用户的家目录  
import { add_tables, add_indexes, alter_tables } from "./Tables"
// 纯 SQL 构造逻辑已抽离到 dbSqlCore（node 中可测），本模块只负责执行。
// 抽离过程修正了两个既有缺陷，见 dbSqlCore.mjs 顶部说明。
//
// ⚠ 只导入本文件**原本没有**的三个符号。本文件原本已本地定义
//   toCamelCase / convertDbObj2BizObj；若再从 dbSqlCore 导入同名符号，
//   vite 会报 `build-import-analysis] Parse error @:1:0`（exit=1）。
//   实测对照：导入本地不存在的符号 → 绿；导入同名符号 → 红。
//   dbSqlCore 中的同名导出供 node 门禁独立复用，两份各自自洽。
import {
    buildColumnsMap,
    buildInsertSql,
    buildUpdateSql
} from "./dbSqlCore.mjs";
const userDir = os.homedir();
const dbFolder = userDir + (NODE_ENV === "development" ? "/.easychatdev/" : "/.easychat/");
if (!fs.existsSync(dbFolder)) {
    fs.mkdirSync(dbFolder);
}
const db = new sqlite3.Database(dbFolder + "local.db");
// 2026-10-04：原为 `new Promise(async (resolve, reject) => { ... resolve() })`。
// 该写法是**真缺陷**：executor 内 await 之后抛出的异常不会被 Promise 捕获，
// 会逃逸成 unhandled rejection，而不是调用方的 .catch() 能接到的 reject ——
// 即建表失败时调用方根本收不到错误，静默失败。改为 async 函数直写：
// 异常自动变 rejection，.catch() 照常工作。
const createTable = async () => {
    for (const item of add_tables) {
        await run(item, []);
    }

    for (const item of add_indexes) {
        await run(item, []);
    }

    // 改表：先判定哪些列真的缺失，再统一执行。
    // 旧实现在循环里「查完 pragma 立刻执行 ALTER」，而 sqlite 的 add column
    // **不幂等**——一旦 pragma 返回空（例如表尚未建好），就会对每张表都执行
    // ALTER，第二次启动即报 duplicate column name。改为收集后再统一执行。
    const pending = [];
    for (const item of alter_tables) {
        const fieldList = await queryAll(`pragma table_info(${item.tableName})`, []);
        const exists = Array.isArray(fieldList) && fieldList.some(row => row && row.name === item.field);
        if (!exists) {
            pending.push(item);
        }
    }
    for (const item of pending) {
        await run(item.sql, []);
    }
    if (pending.length > 0) {
        console.log(`[ADB] 本次启动补列 ${pending.length} 项：${pending.map(i => i.tableName + '.' + i.field).join(', ')}`);
    }
}

const toCamelCase = (str) => {
    return str.replace(/_([a-z])/g, function (match, p1) {
        return String.fromCharCode(p1.charCodeAt(0) - 32);
    });
}

const convertDbObj2BizObj = (data) => {
    if (!data) {
        return null;
    }
    const bizData = {};
    for (let item in data) {
        bizData[toCamelCase(item)] = data[item];
    }
    return bizData;
}
//所有表字段和属性对应关系
const globalColumnsMap = {};

//新增，修改，删除
const run = (sql, params) => {
    //console.log(`执行的sql:${sql},params:${params}`);
    return new Promise((resolve, reject) => {
        const stmt = db.prepare(sql);
        stmt.run(params, function (err, row) {
            if (err) {
                console.error(`执行的sql:${sql},params:${params},执行失败:${err}`);
                reject("查询数据库失败");
            }
            console.log(`执行的sql:${sql},params:${params}执行记录数:${this.changes}`);
            resolve(this.changes);
        });
        stmt.finalize();
    }).catch(error => {
        console.error(error);
    })
}
const queryCount = (sql, params) => {
    return new Promise((resolve, reject) => {
        const stmt = db.prepare(sql);
        stmt.get(params, function (err, row) {
            console.log(`执行的sql:${sql},params:${params},row:${row}`);
            if (err) {
                console.error(err);
                //reject("查询数据库失败");
                resolve(0);
            }
            resolve(Array.from(Object.values(row))[0]);
        });
        stmt.finalize();
    })
}

//查询单个
const queryOne = (sql, params) => {
    return new Promise((resolve, reject) => {
        const stmt = db.prepare(sql);
        stmt.get(params, function (err, row) {
            if (err) {
                console.error(err);
                // reject("查询数据库失败");
                resolve({});
            }
            resolve(convertDbObj2BizObj(row));
            console.log(`执行的sql:${sql},params:${params},row:${JSON.stringify(row)}`);
        });
        stmt.finalize();
    })
}

//查询所有
const queryAll = (sql, params) => {
    return new Promise((resolve, reject) => {
        const stmt = db.prepare(sql);
        stmt.all(params, function (err, row) {
            if (err) {
                console.error(err);
                //reject("查询数据库失败");
                resolve([]);
            }
            row.forEach((item, index) => {
                row[index] = convertDbObj2BizObj(item);
            })
            console.log(`执行的sql:${sql},params:${params},row:${JSON.stringify(row)}`);
            resolve(row);
        });
        stmt.finalize();
    })
}


const insert = (sqlPrefix, tableName, data) => {
    // 纯核心顺带返回 dropped：字段不在列映射里时**报出来**而非静默丢弃。
    // 旧实现直接跳过，调用方无从得知「这次写入根本没生效」（草稿/免打扰列缺失
    // 时的历史事故即此类）。零依赖：node_modules 太大，不引入测试框架。
    const { sql, params, dropped } = buildInsertSql(
        sqlPrefix, tableName, data, globalColumnsMap[tableName]);
    if (dropped.length > 0) {
        console.warn(`[ADB] ${tableName} 写入时以下字段不在列映射中，已被丢弃（写入未生效）：${dropped.join(', ')}`);
    }
    if (params.length === 0) {
        console.warn(`[ADB] ${tableName} 无有效字段，跳过写入`);
        return Promise.resolve(0);
    }
    return run(sql, params);
}

const insertOrReplace = (tableName, data) => {
    return insert("insert or replace into", tableName, data);
}

const insertOrIgnore = (tableName, data) => {
    return insert("insert or ignore into", tableName, data);
}


const update = (tableName, data, paramData) => {
    // 纯核心修掉了缺陷①：旧实现在 where 条件上用 `if (paramData[item])` 真值过滤，
    // 空串 / 0 会被**静默丢弃** → 拼出的 WHERE 少一个条件 → 本该只命中一行的更新
    // 变成改写该用户的所有会话。改判 undefined / null。
    const { sql, params, skipped, reason, dropped } = buildUpdateSql(
        tableName, data, paramData, globalColumnsMap[tableName]);
    if (dropped.length > 0) {
        console.warn(`[ADB] ${tableName} 更新时以下字段不在列映射中，已被丢弃（更新未生效）：${dropped.join(', ')}`);
    }
    //空 set / 空 where 都拼不出合法且安全的 SQL：前者报 SQLITE_ERROR(near "where")
    //并弹原生框阻塞主进程，后者会退化成全表更新。短路不写库，返回 0 与 run() 口径一致。
    if (skipped) {
        console.warn(`update ${tableName} 跳过执行：${reason}`);
        return Promise.resolve(0);
    }
    return run(sql, params);
}

//初始化获取所有字段列
const initTableColumnsMap = async () => {
    let sql = "select name from sqlite_master WHERE type='table' and name!='sqlite_sequence'";
    let tables = await queryAll(sql, []);
    for (let i = 0; i < tables.length; i++) {
        sql = `PRAGMA table_info(${tables[i].name})`;
        let columns = await queryAll(sql, []);
        globalColumnsMap[tables[i].name] = buildColumnsMap(columns);
    }
}

const init = () => {
    db.serialize(async () => {
        await createTable();
        initTableColumnsMap();
    });
}

init();

export {
    run,
    queryOne,
    queryCount,
    queryAll,
    insertOrReplace,
    insertOrIgnore,
    update,
    insert
};