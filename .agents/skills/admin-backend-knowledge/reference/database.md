# database.md — 数据库结构与连接

> 本文件回答「**有哪些表、每个表什么列、怎么连库核对**」。实体语义与关系见 [domain-model.md](domain-model.md)；Po 写法见 [pojo-mapper.md](pojo-mapper.md)。

## 1. 连接方式（需要核对表结构/数据时直接连库）

- 连接信息取自 `application.yml` → `ds.datasource.pool.datasources.master`：
  `jdbc:mysql://dev.meichis.com:3306/ruoyi`，账号 `root` / `meichis1!`，HikariCP。
- 本机**没有 `mysql` CLI**；用 JDK 的 `jshell` + 本地 Maven 仓库的 MySQL 驱动直连：

```bash
JAR=$(ls /d/repository/com/mysql/mysql-connector-j/*/mysql-connector-j-*.jar | grep -vE "sources|javadoc" | tail -1)
printf '%s\n' 'import java.sql.*;' \
'var c=DriverManager.getConnection("jdbc:mysql://dev.meichis.com:3306/ruoyi?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","meichis1!");' \
'var rs=c.createStatement().executeQuery("select table_name from information_schema.tables where table_schema=database() order by table_name");' \
'while(rs.next()) System.out.println("T| "+rs.getString(1));' \
'c.close(); /exit' | /d/program/openjdk/j17/bin/jshell.exe -q --class-path "$JAR" -
```

> 复杂 SQL 建议写入临时 `.jsh` 脚本后 `jshell -q --class-path "$JAR" - < 脚本`，避免 shell 引号转义；用完删除临时文件。

## 2. 表结构（14 张业务表）

库 `ruoyi`（MySQL），`utf8mb4`。主键 `id` 为 `varchar(64)`（雪花字符串）。下表「业务列」不含审计基类列。

| 表 | 说明（库注释） | Po | 基类 | 业务列 |
|----|----------------|----|------|--------|
| `sys_api` | 系统接口 | `SysApi` | `AllBasePo` | `dir_id`、`api_code`、`request_type_list`(json)、`backend_full_class_name`、`api_name`、`api_path`、`api_style`、`enable_flag`、`auth_check`、`log_req`、`log_resp`、`api_desc`、`should_del`、`del_flag` |
| `sys_config` | 系统配置 | `SysConfig` | `AllBasePo` | `config_code`、`config_name`、`config_value`、`enable_flag`、`config_desc`、`del_flag` |
| `sys_dict` | 系统字典 | `SysDict` | `AllBasePo` | `dict_code`、`dict_name`、`dict_sort`、`dict_desc`、`del_flag` |
| `sys_dict_item` | 系统字典项 | `SysDictItem` | `AllBasePo` | `dict_id`、`dict_code`、`dict_item_code`、`dict_item_name`、`enable_flag`、`item_sort`、`item_desc`、`custom_prop`(json)、`del_flag` |
| `sys_dir` | 系统目录 | `SysDir` | `AllBasePo` | `dir_type_code`、`dir_code`、`parent_dir_id`、`dir_name`、`dir_desc`、`del_flag` |
| `sys_file` | 文件上传记录 | `SysFile` | `AllBasePo` | `file_name`、`ext_name`、`oss_name`、`file_path`、`file_size`（**KB**）、`large_flag`、`status`（1=完成）；**无 `del_flag`** |
| `sys_resource` | 系统资源 | `SysResource` | `AllBasePo` | `platform_code`、`resource_code`、`resource_name`、`parent_resource_id`、`resource_type`、`enable_flag`、`icon_code`、`visit_flag`、`resource_sort`、`resource_desc`、`del_flag` |
| `sys_resource_api_rel` | 资源接口关联 | `SysResourceApiRel` | `CreateBasePo` | `resource_id`、`api_id`；**无 `del_flag`** |
| `sys_resource_ext` | 系统资源扩展 | `SysResourceExt` | `AllBasePo` | `resource_id`、`channel_code`、`config_info`(json)、`del_flag` |
| `sys_role` | 系统角色 | `SysRole` | `AllBasePo` | `platform_code`、`role_code`、`role_name`、`enable_flag`、`role_desc`、`del_flag` |
| `sys_role_resource_rel` | 角色资源关联 | `SysRoleResourceRel` | `CreateBasePo` | `role_id`、`resource_id`；**无 `del_flag`** |
| `sys_user` | 系统用户 | `SysUser` | `AllBasePo` | `username`、`user_nickname`、`head_img_url`、`password`（MD5）、`enable_flag`、`sex`、`email`；**无 `del_flag`** |
| `sys_user_platform` | 用户平台关联 | `SysUserPlatform` | **无基类** | `platform_code`、`user_id` |
| `sys_user_role_rel` | 用户角色关联 | `SysUserRoleRel` | `CreateBasePo` | `user_id`、`role_id`；**无 `del_flag`** |

## 3. 字段与建表约定

- **逻辑删除** `del_flag`：`N`=未删除、`Y`=已删除。`application.yml` 配置 `logic-delete-field=del_flag`、`logic-delete-value=Y`、`logic-not-delete-value=N`，Po 字段加 `@TableLogic`。
- **无 `del_flag` 的表**：`sys_user`、`sys_file`、`sys_resource_api_rel`、`sys_role_resource_rel`、`sys_user_role_rel`、`sys_user_platform`（删除为物理删除）。
- **启用标识** `enable_flag`：`Y` / `N`。
- **关联表** `sys_*_rel` 继承 `CreateBasePo`（仅 `create_user_id` / `create_time`）。
- **仓库内没有 SQL 迁移文件**：加列/改列需人工在库上执行 DDL，再同步 Po 字段。
- **给已有表/实体加字段的标准步骤**：① 库上加列 → ② 对应 `Po` 加字段（带中文注释）→ ③ 编辑入参 `XxxEditDto` 补同名字段 → ④ 继承 Po 的 `ListVo` / `NodeVo` **无需改**（`*MSImpl` 自动映射）；**独立声明型 Vo（如 `SysResourceTreeNodeVo`）必须手工补字段** → ⑤ `mvn compile -pl application` 后核对 `target/generated-sources`。
- 多处以字典项编码作为取值来源，见 [domain-model.md](domain-model.md) 第 2.4 节。

## 4. 系统字典内容（`sys_dict` / `sys_dict_item` 实测）

| 字典编码 | 名称 | 字典项编码 | 使用位置 |
|----------|------|------------|----------|
| `PLATFORM` | 平台类型 | `WEB`、`APP` | `sys_user_platform.platform_code`、`sys_role.platform_code`、`sys_resource.platform_code`；对应 `PlatformConst` |
| `SEX` | 性别 | `M`、`F` | `sys_user.sex` |
| `API_DIR_TYPE` | 接口目录类型 | `API_DIR` | `sys_dir.dir_type_code` |
| `RESOURCE_TYPE` | 资源类型 | `DIR`、`PAGE`、`BUTTON` | `sys_resource.resource_type` |
| `PLATFORM_WEB_RESOURCE_PROP` | web 端资源属性 | `path`、`componentName`、`h5LinkFlag` | 与 `SysResourceExtConfigInfo` 字段一一对应，即 `sys_resource_ext.config_info` 的 JSON 结构 |
| `PLATFORM_PROP_REL` | 平台资源定义映射 | `WEB`（值为另一字典编码） | 平台 → 该平台使用的资源属性字典 |
| `WITHOUT_FLUSH_API_CODE` | api 刷新白名单 | 字典项编码即 `sys_api.api_code`（如 `SYS_ERR`） | 接口自动同步跳过名单；常量 `DsConst.WITHOUT_FLUSH_API_CODE` |

## 5. 种子数据与规模（实测）

| 表 | 行数 | ID 前缀分布 |
|----|------|-------------|
| `sys_api` | 74 | `14`×62、`20`×8、`21`×4 |
| `sys_resource` | 44 | `15`×42、`20`×2 |
| `sys_role_resource_rel` | 128 | — |
| `sys_resource_api_rel` | 50 | — |
| `sys_dict_item` | 13 | — |
| `sys_dir` | 10 | — |
| `sys_dict` | 7 | — |
| `sys_resource_ext` | 7 | — |
| `sys_config` | 5 | — |
| `sys_role` / `sys_user` / `sys_user_platform` / `sys_user_role_rel` | 各 4 | — |
| `sys_file` | 0 | — |

约定：种子 ID 为 19 位数字字符串，按表分段（`sys_api` 以 `14`、`sys_resource` 以 `15` 为主，另有少量历史数据落在其它段）；新增数据用雪花 ID，不沿用分段。
