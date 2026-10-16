# pojo-mapper.md — POJO、Mapper 与 MapStruct 模板清单

> 本文件提供「**照抄即可**」的模板与**实际清单**；对应的强制规则见 [../SKILL.md](../SKILL.md) 第 3、4 节。

## 1. 后缀与包

| 后缀 | 包 | 用途 | 基类 |
|------|----|------|------|
| `Po` | `pojo.po` | 数据库实体，字段与表列严格 1:1 | `AllBasePo` / `CreateBasePo` / 无 |
| `Dto` | `pojo.dto` | 编辑/传输对象（`@RequestBody`，扁平结构，可聚合子表 List） | 无 |
| `Ro` | `pojo.ro` | 查询参数 | `PageQueryRo`（分页）或无 |
| `Vo` | `pojo.vo` | 视图/响应对象（`R<XxxVo>`） | 见下方「Vo 的两种落点」 |

类名 = PascalCase + 后缀：`SysApiQueryRo`、`BatchDeleteDto`、`SysResourceTreeNodeVo`。

**Vo 的两种落点**（加字段时影响不同）：

| 落点 | 特征 | 现有类 |
|------|------|--------|
| 继承 Po 型 | `extends XxxPo`（列表类再 `implements UserNameFillable` 回填审计人） | `SysApiListVo`、`SysConfigListVo`、`SysDictListVo`、`SysDictItemListVo`、`SysRoleListVo`、`SysUserListVo`、`SysResourceListVo`、`SysDirNodeVo`、`SysRoleResourceRelVo`、`SysUserRoleRelVo`、`SysUserPlatformVo` |
| 独立声明型 | 不继承 Po，自行声明字段 | `SysResourceTreeNodeVo`、`SysDictSelectListVo`、`SysDirSelectNodeVo`、`SysResourceApiOverviewVo`，以及 `LoginVo`、`LoginSelectRoleVo`、`CaptchaVo`、`FileUploadVo`、`FileChunkInitVo`、`DsSpringApiVo` |

> ⚠️ **给 Po 加字段时**：继承 Po 型自动跟随（`*MSImpl` 自动映射），**独立声明型必须手工加同名字段**，否则新字段不会出现在接口响应里。
> `implements UserNameFillable` 仅用于需要回填 `createUserName` / `updateUserName` 的 Vo（`SysRoleResourceRelVo`、`SysUserRoleRelVo`、`SysUserPlatformVo` 继承 Po 但不回填）。

## 2. 审计基类字段

| 基类 | 包 | 字段 |
|------|----|------|
| `AllBasePo` | `com.ds.commonPojo.pojo.po` | `createUserId`（`FieldFill.INSERT`）、`createTime`、`updateUserId`（`FieldFill.INSERT_UPDATE`）、`updateTime` |
| `CreateBasePo` | `com.ds.commonPojo.pojo.po` | `createUserId`（`FieldFill.INSERT`）、`createTime` |
| `BasePo` | `org.ds.dsBaseCommon.pojo.po` | PO 根接口 |

`UserNameFillable` 声明 `getCreateUserId` / `getUpdateUserId` / `setCreateUserName` / `setUpdateUserName`。

> ⚠️ `FieldFill` 当前**不会生效**：整个框架未提供 `MetaObjectHandler`。审计字段实际由 `BuzContextUtil`（读取侧，`AuthFilter` 每次请求调用 `requestInit` 写入上下文）+ 个别 Mapper 手动 `set` 维护，详见 [../SKILL.md](../SKILL.md) 现状偏差。

## 3. Po 模板

```java
@TableName(value = "sys_xxx", autoResultMap = true)   // 显式表名 + 开启 autoResultMap
@Getter
@Setter
public class SysXxx extends AllBasePo {                // 或 extends CreateBasePo / 不继承
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    // ...数据库字段，禁止出现 @TableField(exist = false)
    @TableLogic
    private String delFlag;
}
```

- JSON 字段：`@TableField(typeHandler = JacksonTypeHandler.class)`。
- 现有 JSON 字段：`SysApi.requestTypeList`（`List<String>`）、`SysDictItem.customProp`（`HashMap<String,String>`）、`SysResourceExt.configInfo`（`SysResourceExtConfigInfo`：`h5LinkFlag` / `path` / `componentName`）。
- `@TableLogic` + `del_flag` 仅存在于有该列的表；`SysUser`、`SysFile` **无** `del_flag`。

## 4. 静态工厂清单

| 类 | 工厂方法 |
|----|----------|
| `SysApi` | `ofAuto(apiCode, apiName, requestTypeList, apiPath, backendFullClassName, apiStyle)` |
| `SysUser` | `ofPassword(id, password)` / `ofProfile(id, userNickname, headImgUrl, sex, email)` |
| `SysFile` | `of(fileName, extName, ossName, filePath, fileSize, largeFlag, status)` |
| `SysResourceApiRel` | `of(resourceId, apiId)` |
| `SysRoleResourceRel` | `of(roleId, resourceId)` |
| `SysUserRoleRel` | `of(userId, roleId)` |
| `SysUserPlatform` | `of(userId, platformCode)` |
| `SysResourceQueryRo` | `of(platformCode, enableFlag, pageSize)` |
| `LoginVo` | `of(token)` / `of(token, selectRoleFlag)` |
| `CaptchaVo` | `of(sessionId, base64)` |
| `DsSpringApiVo` | `of(requestTypeList, path, handler)` |
| `FileChunkInitVo` | `of(uploadId, uploadedChunks)` |
| `FileUploadVo` | `of(url, bucketName, objectKey, fileName, fileSize)` |
| `UserInfoCacheDto` | `of(userId, username, roleCode, ...)` |

与 Po 强绑定的字段取值常量定义在 Po 内：`SysFile.LARGE_FLAG_YES` / `LARGE_FLAG_NO` / `STATUS_COMPLETED`。

## 5. Vo 模板

```java
@Getter
@Setter
public class SysXxxListVo extends SysXxx implements UserNameFillable {
    private String createUserName;
    private String updateUserName;
}
```

> **给 Po 加字段时的连锁影响**：继承 Po 的 Vo 与 `*MSImpl` 自动跟随，**无需改动**；**独立声明型 Vo（如 `SysResourceTreeNodeVo`）必须手工加同名字段**，否则新字段不出现在响应中。编辑入参 `XxxEditDto` 同理。

## 6. Dto 模板

```java
@Getter
@Setter
public class SysXxxEditDto {
    private String id;
    @Valid @NotNull(message = "xxx不能为空")
    private SysXxx xxx;
    @Valid
    private List<SysXxxExtDto> extensions;   // 子表，可选
    private List<String> apiIdList;          // 关联 id 列表，可选
}
```

## 7. Mapper 模板与命名

```java
@Mapper
public interface SysXxxMapper extends DsBaseMapper<SysXxx> {

    /** 分页查询 */
    default Page<SysXxx> pageList(SysXxxQueryRo ro) {
        return selectPage(Page.of(ro.getPageNum(), ro.getPageSize()), buildQuery(ro));
    }

    /** 列表查询 */
    default List<SysXxx> list(SysXxxQueryRo ro) {
        return selectList(buildQuery(ro));
    }

    /** 根据编码查询 */
    default SysXxx selectByCode(String code) {
        return selectOne(Wrappers.<SysXxx>lambdaQuery().eq(SysXxx::getCode, code));
    }

    /** 根据关联ID删除 */
    default void deleteByResourceId(String resourceId) {
        delete(Wrappers.<SysXxx>lambdaQuery().eq(SysXxx::getResourceId, resourceId));
    }

    /** 条件构造：仅 Mapper 内部使用 */
    default LambdaQueryWrapper<SysXxx> buildQuery(SysXxxQueryRo ro) {
        LambdaQueryWrapper<SysXxx> qw = Wrappers.lambdaQuery();
        if (StringUtils.hasText(ro.getCode())) qw.like(SysXxx::getCode, ro.getCode());
        qw.orderByDesc(SysXxx::getCreateTime);
        return qw;
    }
}
```

命名约定：分页 `pageList`、列表 `list` / `listByQuery`、单条 `selectByXxx` / `selectOneByXxx`、批量 `selectByXxxs` / `selectByIds`、全量 `selectAll`、删除 `deleteByXxx`、统计 `countByXxx`、缓存装载 `loadCacheItem`。

### 7.1 当前 Mapper 实现方式分布

| Mapper | default 方法 | 声明式（XML / `@Select`） |
|--------|--------------|--------------------------|
| `SysApiMapper` | `selectAll`、`pageList`、`list`、`selectOneByApiCode` | — |
| `SysConfigMapper` | `pageList`、`list`、`selectByCode`、`selectAllConfig` | `selectONEByConfigCode` |
| `SysDictMapper` | `pageList`、`list`、`selectByCode`、`selectAllCacheFlushCode` | — |
| `SysDictItemMapper` | `selectEnabledByDictId`、`loadCacheItem`、`pageList`、`list`、`deleteByDictId` | `selectALLDictItemByDictCodeList`、`selectONEDictItemByDictCodeList` |
| `SysDirMapper` | `listByQuery` | — |
| `SysFileMapper` | 无方法（空 Mapper） | — |
| `SysResourceMapper` | `pageList`、`list`、`buildQuery` | — |
| `SysResourceExtMapper` | `deleteByResourceId`、`selectByResourceId`、`selectByResourceIds` | — |
| `SysResourceApiRelMapper` | `deleteByResourceId`、`selectByResourceId`、`selectByResourceIds` | `selectApiOverviewByResourceIds` |
| `SysRoleMapper` | `pageList`、`list`、`selectEnabledByCodes`、`selectByRoleCodeAndPlatform` | — |
| `SysRoleResourceRelMapper` | `deleteByRoleId`、`selectByRoleId` | — |
| `SysUserRoleRelMapper` | `deleteByUserId`、`selectByUserId` | — |
| `SysUserPlatformMapper` | `deleteByUserId`、`selectByUserId`、`countByUserIdAndPlatformCode` | — |
| `SysUserMapper` | `selectByUsername` | `pageList`、`list` |
| `SysRbacCommonMapper`（跨模块） | — | `findAll`、`getSysRoleAuthApi`、`loadUserInfoById`、`selectOneApi` |
| `SysUserCommonMapper`（跨模块） | — | `selectUserFillByUserIdList` |

XML 位置：`application/src/main/resources/mapper/*.xml`、`common-mapper/src/main/resources/mapper/*.xml`。

## 8. MapStruct 映射器清单

位置 `com.ds.single.mapstruct`，均 `extends MapStructExt` 且 `@Mapper(config = MapperCentralConfig.class)`。

| 映射器 | 方向 / 方法 |
|--------|-------------|
| `MapperCentralConfig` | 公共配置（非映射器）：`unmappedTargetPolicy = IGNORE`、`builder = @Builder(disableBuilder = true)` |
| `SysEditDtoPojoMS` | Dto → Po：`toPo(...)` × 10（Api / Config / Dict / DictItem / Dir / Role / User / Resource / ResourceExt / ResourceApiRel） |
| `SysListVoPojoMS` | Po → ListVo：`toVo(...)` × 10、`toDictSelectVo(SysDict)` |
| `SysResourcePojoMS` | `toTreeNodeVo(SysResource)` → `SysResourceTreeNodeVo` |
| `SysDirPojoMS` | `toNodeVo(SysDir)` → `SysDirNodeVo`、`toNodeSelectVo(SysDir)` → `SysDirSelectNodeVo` |
| `SysRolePojoMS` | `toVo(SysRole)` → `LoginSelectRoleVo` |
| `SysApiPojoMS` | `toCacheDto(SysApi)` → `ApiCacheDto` |
| `SysConfigPojoMS` | `toCacheDto(SysConfig)` → `SysConfigCacheDto` |
| `SysDictItemPojoMS` | `toCacheDto(SysDictItem)` → `SysDictItemCacheDto` |
| `SysDictPojoMS` | 占位映射器（当前无映射方法） |

## 9. 用户信息填充（`createUserName` / `updateUserName`）

```text
1. Vo implements UserNameFillable
2. MapStruct 转 Vo：SysListVoPojoMS.INSTANCE.toPojoList(list, SysListVoPojoMS.INSTANCE::toVo)
3. userInfoFillUtil.fillUserNames(voList)
     └─ 收集 createUserId/updateUserId → SysUserCommonMapper.selectUserFillByUserIdList
        → 组装 id→username → 回填 createUserName/updateUserName
```

单条详情也走同一方法：`userInfoFillUtil.fillUserNames(CollectionUtil.newArrayList(vo))`。
