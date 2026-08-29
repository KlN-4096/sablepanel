# SablePanel

![SablePanel icon](src/main/resources/icon.png)

## 中文

SablePanel是针对Sable物理结构的网页管理面板.它可以对已有物理结构进行一致性分析,同时可以处理重复副本,远距离断链残骸,悬空holding指针和失效追踪点;此外,面板还提供结构索引,缩略图,3D预览,传送,常驻,暂停和回收站恢复.性能分析默认关闭,可在面板中手动开启.

### 基础功能

- 扫描已加载与磁盘中的Sable物理体,并按依赖关系组成物理组
- 定位孤儿条目,重复副本,远距离断链残骸,悬空holding指针和失效追踪点
- 显示结构名称,位置,运行状态,方块统计,缩略图和完整3D预览
- 提供传送,常驻加载,暂停物理,暂停方块实体Tick和孤儿结构收养
- 通过带验收和备份的回收站完成安全删除与恢复
- 支持手动开启的性能分析和同机多服务端集群

### 安装与访问

- 服务端安装是必需的:服务端负责读取和操作物理结构.网页默认位于`http://服务器地址:25580/`,TLS数据端口默认为`25581`.
- 客户端安装是可选的:客户端只提供`http://localhost:25580/`本地网页网关,并通过TLS连接服务器的`25581`端口;真正的数据和操作仍由服务端处理.
- 访问令牌:令牌是面板密码,首次启动默认为`sablepanel`,保存在`config/sablepanel/sablepanel-server.json`,也可在面板维护页修改.25580是明文HTTP,只应在本机,局域网或隧道内使用.

### 兼容性

- Minecraft 1.21.1
- NeoForge 21.1.228或更高版本
- 已验证Sable 2.0.3和2.0.4;其他Sable版本尚未验证,不提供兼容性保证

### AI使用说明

本项目代码主要由KlN-4096,gpt-5.6-sol和fable5共同完成.开发过程中使用AI协助代码实现,重构,调试和审查.

### 许可证

SablePanel使用[MIT License](LICENSE).随包组件的许可证见[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

## English

SablePanel is a web management panel for Sable physical structures. It analyzes the consistency of existing structures and handles duplicate copies, distant detached remnants, dangling holding pointers, and stale tracking points. It also provides structure indexing, thumbnails, full 3D previews, teleporting, force-loading, pausing, and recycle-bin restoration. Performance profiling is disabled by default and can be enabled manually.

### Core features

- Scans loaded and on-disk Sable bodies and groups them by dependency relationships
- Detects orphaned entries, duplicate copies, distant detached remnants, dangling holding pointers, and stale tracking points
- Displays structure names, positions, runtime states, block statistics, thumbnails, and full 3D previews
- Provides teleporting, force-loading, physics pausing, block-entity tick pausing, and orphan adoption
- Performs safe deletion and restoration through a verified, backed-up recycle bin
- Supports manually enabled performance profiling and multiple server instances on the same machine

### Installation and access

- Server installation is required: the server reads and operates on physical structures. The web panel defaults to `http://server-address:25580/`, while the TLS data endpoint defaults to port `25581`.
- Client installation is optional: the client only provides a local gateway at `http://localhost:25580/` and connects to the server's port `25581` over TLS. All authoritative data and operations remain on the server.
- Access token: the token is the panel password. Its first-run default is `sablepanel`; it is stored in `config/sablepanel/sablepanel-server.json` and can be changed from the Maintenance page. Port 25580 uses plain HTTP and should only be used locally, on a LAN, or through a tunnel.

### Compatibility

- Minecraft 1.21.1
- NeoForge 21.1.228 or newer
- Tested with Sable 2.0.3 and 2.0.4; other Sable versions are unverified and unsupported

### AI-assisted development

The project code was created primarily by KlN-4096, gpt-5.6-sol, and fable5. AI was used to assist with implementation, refactoring, debugging, review, and documentation.

### License

SablePanel is licensed under the [MIT License](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for bundled third-party components.
