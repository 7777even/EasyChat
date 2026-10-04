"""把 ADB.js 的真实改动重做一遍，规避「同名重复声明」。

根因（2026-10-04 实测，_verify_name.py 对照实验确认）：
  ADB.js 原本就有**本地** const toCamelCase / convertDbObj2BizObj。
  若再从 dbSqlCore 导入同名符号，vite 报
  `vite:build-import-analysis] Parse error @:1:0`（exit=1）。
  对照组：导入**本地不存在**的符号（buildColumnsMap）→ 绿。
  即「同名重复声明」才是触发点，与 .mjs 扩展名无关（两种写法都红）。

修法：ADB.js 只导入它**尚不存在**的三个函数
     （buildColumnsMap / buildInsertSql / buildUpdateSql），
     本地已有的 toCamelCase / convertDbObj2BizObj 保持原样不动。
     dbSqlCore 里的同名导出供 node 门禁独立复用，两份各自自洽。
用法：python engineering/qa/_apply_adb.py
"""
import io
import subprocess

ADB = 'easychat-front/src/main/db/ADB.js'
HEAD = subprocess.run(['git', 'show', 'HEAD:' + ADB], capture_output=True,
                      cwd='D:/qd/EasyChat').stdout.decode('utf-8')

IMPORT_OLD = 'import { add_tables, add_indexes, alter_tables } from "./Tables"'
IMPORT_NEW = (IMPORT_OLD + '\n'
              '// 纯 SQL 构造逻辑已抽离到 dbSqlCore（node 中可测），本模块只负责执行。\n'
              '// 抽离过程修正了两个既有缺陷，见 dbSqlCore.mjs 顶部说明。\n'
              '//\n'
              '// ⚠ 只导入本文件**原本没有**的三个符号。本文件原本已本地定义\n'
              '//   toCamelCase / convertDbObj2BizObj；若再从 dbSqlCore 导入同名符号，\n'
              '//   vite 会报 `build-import-analysis] Parse error @:1:0`（exit=1）。\n'
              '//   实测对照：导入本地不存在的符号 → 绿；导入同名符号 → 红。\n'
              '//   dbSqlCore 中的同名导出供 node 门禁独立复用，两份各自自洽。\n'
              'import {\n'
              '    buildColumnsMap,\n'
              '    buildInsertSql,\n'
              '    buildUpdateSql\n'
              '} from "./dbSqlCore.mjs";')

COLUMNSMAP_OLD = """        const columnsMapItem = {};
        for (let j = 0; j < columns.length; j++) {
            columnsMapItem[toCamelCase(columns[j].name)] = columns[j].name;
        }
        globalColumnsMap[tables[i].name] = columnsMapItem;"""
COLUMNSMAP_NEW = "        globalColumnsMap[tables[i].name] = buildColumnsMap(columns);"

INSERT_OLD = """const insert = (sqlPrefix, tableName, data) => {
    const columnsMap = globalColumnsMap[tableName];
    const dbColumns = [];
    const params = [];
    for (let item in data) {
        if (data[item] != undefined && columnsMap[item] != undefined) {
            dbColumns.push(columnsMap[item]);
            params.push(data[item]);
        }
    }
    const preper = '?'.repeat(dbColumns.length).split("").join(",");
    const sql = `${sqlPrefix} ${tableName}(${dbColumns.join(",")})values(${preper})`;
    return run(sql, params);
}"""
INSERT_NEW = """const insert = (sqlPrefix, tableName, data) => {
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
}"""

UPDATE_HEAD_START = 'const update = (tableName, data, paramData) => {'
UPDATE_HEAD_END = '\n//初始化获取所有字段列'
UPDATE_NEW = """const update = (tableName, data, paramData) => {
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
"""

ALTER_OLD = """        for (const item of alter_tables) {
            const fieldList = await queryAll(`pragma table_info(${item.tableName})`, []);
            const field = fieldList.some(row => row.name === item.field);
            if (!field) {
                await run(item.sql, []);
            }
        }"""
ALTER_NEW = """        // 改表：先判定哪些列真的缺失，再统一执行。
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
        }"""


def main():
    s = HEAD
    for name, find, repl in (
        ('import', IMPORT_OLD, IMPORT_NEW),
        ('buildColumnsMap', COLUMNSMAP_OLD, COLUMNSMAP_NEW),
        ('insert', INSERT_OLD, INSERT_NEW),
        ('alter', ALTER_OLD, ALTER_NEW),
    ):
        if find not in s:
            raise AssertionError(f'{name} 锚点未命中')
        s = s.replace(find, repl)

    a = s.index(UPDATE_HEAD_START)
    b = s.index(UPDATE_HEAD_END)
    s = s[:a] + UPDATE_NEW + s[b:]

    io.open(ADB, 'w', encoding='utf-8', newline='').write(s)
    print('已应用全部改动（含 update 段整体替换）')

    rc = subprocess.run(
        'cmd /c "npm run build > %TEMP%\\opencode\\fe_final.txt 2>&1"',
        shell=True, cwd='easychat-front').returncode
    print('build exit =', rc)
    out = io.open(r'C:\Users\7even\AppData\Local\Temp\opencode\fe_final.txt',
                  encoding='utf-8', errors='replace').read()
    for line in out.replace('\r\n', '\n').split('\n'):
        if 'Parse error' in line or 'error during' in line or 'built in' in line:
            print(line.strip()[:150])


if __name__ == '__main__':
    main()