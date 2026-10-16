# conventions.md — 构建、代码风格、提交与自检清单

> 本文件回答「**怎么编译、代码格式怎么统一、提交怎么做、改完怎么自检**」。
> 各领域规则见 [../SKILL.md](../SKILL.md)。

## 1. 构建与命令

```bash
# 必须先设置 JDK 17
export JAVA_HOME="D:/program/openjdk/j17" && export PATH="$JAVA_HOME/bin:$PATH"

# 改动 common-* 后必须先 install 到本地仓库
mvn install -pl common-pojo,common-mapper,common-service -DskipTests

# 再编译 application
mvn compile -pl application

# 全量构建
mvn clean install -DskipTests
```

- Maven 路径 `/d/program/maven/bin/mvn`，**无 Maven Wrapper**。
- 环境：JDK 17、Node v24（前端工具不涉及）、Windows + Git Bash。

**验证顺序（改完代码固定按此执行）**：

1. `mvn install -pl common-pojo,common-mapper,common-service -DskipTests`（改了 `common-*` 时必做）
2. `mvn compile -pl application`
3. 若改了 MapStruct 映射：检查 `application/target/generated-sources` 下 `*MSImpl` 是否已按预期重新生成
4. 若改了表结构/Po：连库核对列（见 [database.md](database.md) 第 1 节）

## 2. 代码风格

| 项 | 约定 |
|----|------|
| 缩进 | 4 空格 |
| 编码 / 换行 | UTF-8；LF（仓库内文档与源码均以 LF 存储） |
| Import | 不使用 `*` 通配符（`mapper` / `pojo` 包内已有 import 除外） |
| 校验 | `jakarta.validation`（`@NotBlank` / `@NotEmpty` / `@NotNull`），提示信息用中文 |
| 集合构造 | `CollectionUtil.newArrayList()`（Hutool），不用 `List.of()` |
| 注入 | `@RequiredArgsConstructor` + `private final`；不用字段 `@Autowired` |
| 分支空值处理 | 工具类用 Hutool `ObjectUtil` / `CollectionUtil` / `StringUtils.hasText(...)` |

## 3. 提交规范

- 提交信息用简洁中文（或英文），聚焦单一目的。
- 不混合「重构」与「新功能」；一次提交只做一件事。
- 引用相关 Issue。
- 历史提交风格示例：`规范调整`、`缓存优化`、`rbac插槽优化`、`注释补录`、`图标修复`。

## 4. 提交前自检清单

> 逐条勾选；任一条不通过就先修，再提交。

**分层与接口**

- [ ] SQL / Wrapper 只出现在 Mapper 的 `default` 方法或 XML / `@Select` 中，Service 内没有 `Wrappers`
- [ ] Controller 返回 Vo（`controller/common` 的既有例外除外），请求体用 Ro/Dto，未使用 `Map`
- [ ] Controller 类上有 `@Validated`、参数有 `@Valid`；响应用 `R.ok` / `R.fail`
- [ ] 分页用 `TableDataUtil.createPageVo(IPage)`

**POJO 与映射**

- [ ] 新增 Po 有 `@TableName(autoResultMap = true)`、`@TableId(ASSIGN_ID)`，字段逐项中文注释
- [ ] 未使用 `new Xxx()` + 连续 `setXxx()`，改用静态工厂 `Xxx.of(...)`
- [ ] 新增字段已同步到编辑入参 `XxxEditDto`
- [ ] MapStruct 映射器以 `MS` 结尾、`extends MapStructExt`、`@Mapper(config = MapperCentralConfig.class)`
- [ ] 批量转换用 `MapStructExt.toPojoList(list, INSTANCE::toXxx)`，无手写循环 / `stream().map().collect()`

**异常与常量**

- [ ] 抛异常一律 `new BuzException(ResultCode.X, "中文消息")`，无 `RuntimeException` / `IllegalStateException`
- [ ] 未新增散落的 `private static final` 魔法值；`Y`/`N` 用 `YesNoConst`
- [ ] 新增常量前已盘点，能复用即复用；key 走常量类的 `static` 拼接方法

**注释**

- [ ] 类 / 接口 / 方法有 javadoc（含 `@param` / `@return`），字段有中文注释
- [ ] Service 业务方法体内有编号步骤注释；查询含「查询→转 Vo→回填审计人→组装」，写操作含「转换→校验→落库→缓存刷新」
- [ ] 全量覆盖语义标注了「先删后插」；缓存刷新标注了「立即刷新/失效 + 延迟 5 秒兜底」

**缓存与 Redis**

- [ ] 新增缓存先在 `CacheConst` 定义 `QuickConfig` 与 key 方法，实现类 `@PostConstruct` + `getOrCreateCache`
- [ ] 写操作后按「立即刷新/失效 + 延迟 5 秒兜底」处理（`micorSchedulePool`）
- [ ] 使用 `RedissonClient`，未引入 `RedisTemplate`；多命令原子操作用 Lua 且脚本集中在 `LuaRedisScript`
- [ ] Lua 的 `ARGV` 传数值类型（毫秒用 `Long`），未传 `String`

**数据库**

- [ ] 表结构变更已人工在库上执行 DDL，并同步 Po（仓库无迁移文件）
- [ ] 未给 `sys_user` / `sys_file` / `sys_*_rel` 误加 `del_flag`
- [ ] 关联表继承 `CreateBasePo`

**构建**

- [ ] 已执行 `mvn install -pl common-*`（如涉及）+ `mvn compile -pl application` 且通过
- [ ] 如改了 MapStruct，已核对 `target/generated-sources`
