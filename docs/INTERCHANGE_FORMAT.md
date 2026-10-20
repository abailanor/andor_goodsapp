# 商品数据交换格式规范 v1

本文定义本 App 的**商品数据导入/导出格式**。用途有两个：

1. **拓客**：把一份商品目录交给潜在客户，对方装机后直接导入即可开始离线使用；
2. **上线迁移**：客户离线使用一段时间后购买了服务，本地积累的商品要推到服务器。

> 本文只管**交换格式**（磁盘上的文件长什么样）。App 内部的 Room 表结构不在本文范围，
> 见 `app/schemas/` 下由 Room 自动导出的 JSON schema。
>
> 服务端协议以 `C:\Users\hhl\IdeaProjects\service1\README.md` 为唯一权威，
> 本文的字段命名与它 §4.4「商品价格与箱规」对齐。

---

## 0. 一句话摘要

三种容器格式，**同一种字段模型**：

| 格式 | 扩展名 | 用途 | 版本识别方式 |
|---|---|---|---|
| **CSV** | `.csv` | 主要格式，可用 Excel / WPS 直接编辑 | **表头**是否含 `box_price` |
| **JSONL** | `.jsonl` | 机器对机器，逐行一个 JSON 对象 | 首行 `_meta.version` |
| **SQLite** | `.db` | **只读兼容**旧版导出文件 | 表 `products` 的列集合 |

导入时按"JSONL → SQLite → CSV"的顺序探测；导出默认产出 CSV，可勾选同时产出 JSONL。

---

## 1. 字段模型

### 1.1 服务器字段（会同步）

| 列名 | 必填 | 类型 | 缺省 | 服务端对应 | 说明 |
|---|---|---|---|---|---|
| `barcode` | ✓ | 文本 | — | `ProductData.barcode` | 商品条码，**主键**。不得为空、不得含逗号/引号/换行 |
| `name` | ✓ | 文本 | — | `ProductData.name` | 商品名 |
| `price` | ✓ | 十进制文本 | — | `ProductData.price` | 卖出单价。**必须当文本处理**，见 §4.3 |
| `box_price` | | 十进制文本 | 空 = 不按整箱卖 | `ProductData.boxPrice` | 卖出整箱价。**空串与 `null` 等价** |
| `box_size` | | 整数 | `0` = 未设置箱规 | `ProductData.boxSize` | 箱规：一箱多少个 |
| `stock` | | 整数 | `0` | `ProductData.stock` | 当前库存。**可以为负**（服务端容忍并标"存疑"） |
| `orderable` | | `0`/`1` | `1` | `ProductData.orderable` | 是否可订 |

字段名用 **snake_case**，与服务端 Redis hash 的字段名（`box_price` / `box_size`）一致。
JSON API 里它们是 camelCase（`boxPrice` / `boxSize`），映射关系是机械的，见 §6。

**`box_size` 不是可选装饰**（README §4.4）：没有它，整箱价就是个孤立数字——
既算不出折合单价，也没法把"买 3 箱"换算成订单里的 `quantity`（`quantity` 是个数，直接用于扣库存）。
所以导入时 `box_price` 非空而 `box_size` 为 0 的行会在报告里**列为警告**（不拒绝导入）。

### 1.2 本地扩展字段（**不会**同步到服务器）

| 列名 | 必填 | 类型 | 说明 |
|---|---|---|---|
| `category` | | 文本 | 分类。400+ 条商品的目录没有分类很难筛选 |
| `note` | | 文本 | 备注 |

⚠ **服务端没有这两个字段**。它们只在本地存在，通过导入/导出携带。
后果必须写清楚，否则用户会以为它们跟着账号走：

- 推到服务器时这两个字段**被丢弃**（`batch-upsert` 的请求体里没有它们）；
- **换设备登录同一账号，这两个字段是空的**——因为服务器的 FULL 同步不会带回来；
- 唯一可靠的携带方式就是本文的导出文件。

### 1.3 元数据（仅 JSONL）

JSONL 的**第一行**是元数据对象，不是商品：

```json
{"_meta":{"format":"s1-products","version":1,"exportedAt":1758000000000,"shop":"丽华金龙","merchantCode":"00001","count":412,"appVersion":"1.0"}}
```

| 键 | 说明 |
|---|---|
| `format` | 固定 `"s1-products"`。不是这个值就拒绝导入 |
| `version` | 格式版本，当前 `1` |
| `exportedAt` | 导出时刻，epoch millis |
| `shop` / `merchantCode` | 导出方的店铺名与编号；离线导出时两者都可缺省 |
| `count` | 后续商品行数。**导入时必须与实际行数核对**，不符要在报告里警告（文件被截断过） |
| `appVersion` | 导出方的 App 版本，排查用 |

CSV 没有元数据行——**表头本身就是版本标记**（§3）。

---

## 2. CSV 格式

### 2.1 表头

**必须**是第一行，列顺序固定：

```
barcode,name,price,box_price,box_size,stock,orderable,category,note
```

- 分隔符固定 `,`；不支持 `;` 或 TAB（Excel 的"分号 CSV"要先另存为逗号分隔）。
- 引用规则遵循 RFC 4180：值里含 `,` `"` 或换行时用 `"` 包裹，内部的 `"` 写成 `""`。
- **列顺序固定**是为了让"表头识别版本"这件事简单可靠；导入时仍按**列名**取值，
  所以额外的未知列会被忽略（向前兼容），缺失的可选列按缺省值处理。

### 2.2 示例

```csv
barcode,name,price,box_price,box_size,stock,orderable,category,note
6901234567890,可口可乐330ml,3.50,80.00,24,97,1,饮料,
6901234567891,雪碧330ml,3.50,,,48,1,饮料,不按整箱卖
6901234567892,云南白药气雾剂,68.00,540.00,10,0,1,药品,保险子装
```

注意第 2 行：`box_price` 与 `box_size` 都留空，表示"不按整箱卖、未设箱规"。
**不要写 `0`** —— `box_price` 为 `0` 是"整箱免费"，与"不按整箱卖"是两回事。

### 2.3 一个合法的 UTF-8 BOM

Excel 存"CSV UTF-8"时会加 `EF BB BF`。导入时必须**剥掉**，否则第一列的列名会变成
`\uFEFFbarcode` 而匹配不上。

---

## 3. 版本识别

| 判定 | 结果 |
|---|---|
| 首行是 JSON 且 `_meta.format == "s1-products"` | JSONL v`_meta.version` |
| 是 SQLite 文件（头 16 字节 `SQLite format 3\0`）且含表 `products` | 旧版 SQLite |
| 表头含 `box_price` | **CSV v1** |
| 表头恰好是 `barcode,name,price`（顺序不限、可有可无尾随空列） | **CSV v0（旧格式）** |
| 其它 | 拒绝，报"无法识别的格式" |

**v0 → v1 的提升规则**：`box_price` = 空、`box_size` = `0`、`stock` = `0`、`orderable` = `1`、
`category`/`note` = 空。即"旧文件里没有的信息一律按未设置处理"，**不要猜**。

---

## 4. 导入的三个硬约束

这三条都是踩过才会知道的坑，实现时必须有对应的测试。

### 4.1 编码探测：中文 Windows 上 Excel 存的通常是 GB18030，不是 UTF-8

顺序：

1. 若前 3 字节是 `EF BB BF` → 按 **UTF-8** 解，并剥掉 BOM；
2. 否则尝试 **UTF-8 严格解码**（`CharsetDecoder.onMalformedInput(REPORT)`）。成功 → UTF-8；
3. 失败 → 按 **GB18030** 解（它是 GBK/GB2312 的超集，能覆盖 Excel 在中文 Windows 上的全部输出）；
4. 两者都失败 → 报错，**不要**用 `Charset.defaultCharset()` 蒙一个。

⚠ 静默用错编码的后果是商品名变成一堆乱码然后**被写进数据库**，用户要手工逐条改。
所以探测失败必须报错而不是猜。导入报告里要写明最终用的是哪个编码。

### 4.2 `0` 前缀整件行的合并（旧数据的约定）

旧版 App 用"**在条码前加 `0` 再插一行**"来表示整件：

```
06901234567890  可口可乐330ml整件  80.00
6901234567890   可口可乐330ml      3.50
```

新模型里这是一条记录：`barcode=6901234567890, price=3.50, box_price=80.00`。

合并规则：

| 情况 | 处理 |
|---|---|
| `0X` 与 `X` 同时存在 | 合并成 `X`：`X.price` → `price`，`0X.price` → `box_price`；`name` 取 `X` 的（去掉尾部的"整件"二字） |
| 只有 `0X`，没有 `X` | **警告**，不导入。缺了单个价就没法定价 |
| 只有 `X` | 直接导入，`box_price` 留空 |
| `0X` 的 `name` 不以"整件"结尾 | 仍按整件行处理（旧数据不总是规范），但在报告里列出 |

`box_size` 在旧模型里**根本不存在**，合并后一律填 `0`，之后要用户手工补。
导入报告必须明确列出"这 N 条有整箱价但没有箱规，开单时无法把整箱换算成个数"。

这套逻辑与 Room 的 `MIGRATION_3_4` 是**同一套代码**，不要写两遍。

### 4.3 价格必须当文本读

Excel 会把 `3.50` 显示并存储成 `3.5`，CSV 里也可能被写成 `3.5`。
而服务端的金额字段**一律是 JSON 字符串**且保留尾零（`"85.00"` 不是 `"85.0"`，
README_FOR_ANDROID §9 第 1 条专门修过这个）。

规则：

- 一律按**字符串**读入，再转 `BigDecimal(String)`（**不要**走 `Double`，金额过 double 有精度问题）；
- 规范化成**两位小数**（`setScale(2, HALF_UP).toPlainString()`）；
- 原始值与规范化后不同的行，在报告里列为提示（例如 `3.5 → 3.50`）。这不是错误，
  但用户应该知道 Excel 动过他的数据；
- 拒绝负数价格与无法解析的值（列为错误行，跳过该行、继续导入其余的）。

---

## 5. 导入行为

### 5.1 冲突策略（用户在界面上选，默认第 1 项）

| 策略 | 行为 |
|---|---|
| **只新增** | `barcode` 已存在则跳过。最安全 |
| **覆盖本地** | 已存在则用文件里的值覆盖 `product_local` 的那一行 |
| **全部替换** | 清空 `product_local` 后全量导入。**不影响 `product_synced`** |

⚠ 三种策略都**只作用于 `product_local`（未同步区）**。`product_synced` 是服务器权威，
只能被同步协议写，导入不许碰它——否则本地与服务器分歧，而分歧是静默的。

### 5.2 导入报告

导入完成后必须给出逐条可查的报告，不能只说"成功"：

```
编码：GB18030（自动探测）
格式：CSV v0（旧格式，已提升到 v1）
总行数：412
  新增：398
  跳过（已存在）：10
  合并整件行：4
错误行：2
  第 57 行：price 无法解析 "面议"
  第 203 行：barcode 为空
警告：36
  有 box_price 但 box_size=0：36 条（开单时无法把整箱换算成个数，需手工补箱规）
提示：
  价格尾零被规范化：89 条（如 3.5 → 3.50）
```

**部分成功是正常结果**，不要因为两行坏数据就整批回滚。

---

## 6. 与服务端模型的映射

| 交换格式 | JSON API（camelCase） | Redis hash |
|---|---|---|
| `barcode` | `barcode` | `product:<code>:<barcode>` 的键 |
| `name` | `name` | `name` |
| `price` | `price` | `price` |
| `box_price` | `boxPrice` | `box_price` |
| `box_size` | `boxSize` | `box_size` |
| `stock` | `stock` | `stock` |
| `orderable` | `orderable` | `orderable` |
| `category` / `note` | **无对应** | **无对应** |

推送通道是 `POST /api/products/batch-upsert`，**一次最多 100 条**（超出 400），
请求体上限 **20KB**（超出 413）。412 条商品要分 5 批。

⚠ `ProductAddRequest.orderable` 是**原始 `boolean`**（不是 `Boolean`），`stock` 是 `int`。
**漏传 `orderable` 会静默变成 `false`**，把商品改成不可订。推送路径必须显式带上全部字段。

⚠ `batch-upsert` 的库存按**绝对值**落地（不是增量），所以重复提交同一批是幂等的；
响应数组顺序与请求一致（`[{"barcode":"...","created":true}, ...]`），可以逐条对账。

⚠ 它需要 `PRODUCT_MANAGE` **且** `PRICE_CHANGE` 两个权限。只有前者的员工调它会 403
——这是刻意的（否则就成了绕过调价权限的后门）。

⚠ 一次推送 400 条会产生 400 条属性流消息，而 `stream:price` 的 `MAXLEN ~ 200`
（`RedisStreamService.java:29`）。窗口被冲光后，全店设备的价格同步都会退化成 FULL
（README §9）。后果不严重（400 条商品的 FULL 约 40KB），但值得把那个常量调大到 500。

---

## 7. 导出行为

- **导出范围**：`product_local` 与 `product_synced` 的**合并视图**（synced 优先），
  即用户在 App 里实际看到的那份目录。
- **不含**：订单、统计、补货清单、筛选条件、服务器地址、任何凭据。
  导出文件是要交给别人的，**绝不能带凭据**。
- 默认写 CSV（UTF-8 **带 BOM**，这样 Excel 双击打开不会乱码）；
  可勾选同时写 JSONL。
- 文件名：`商品目录_<yyyyMMdd_HHmmss>.csv` / `.jsonl`，写到 `Downloads`
  （`MediaStore`，API 29+；沿用现有 `ProductViewModel.exportDatabaseToDownloads` 的路径）。
- **旧的 SQLite 导出保留**，但改名为"导出为旧版数据库文件（兼容）"，
  只含 `barcode,name,price` 三列，供还在用旧版 App 的客户。

---

## 8. JSONL 格式

第一行是 `_meta`（§1.3），其后每行一个商品对象，**UTF-8 无 BOM**、行尾 `\n`：

```jsonl
{"_meta":{"format":"s1-products","version":1,"exportedAt":1758000000000,"shop":"丽华金龙","merchantCode":"00001","count":2,"appVersion":"1.0"}}
{"barcode":"6901234567890","name":"可口可乐330ml","price":"3.50","box_price":"80.00","box_size":24,"stock":97,"orderable":true,"category":"饮料","note":""}
{"barcode":"6901234567891","name":"雪碧330ml","price":"3.50","box_price":null,"box_size":0,"stock":48,"orderable":true,"category":"饮料","note":""}
```

- 金额一律是**字符串**；`box_price` 未设置时是 `null`（不是 `""`、不是 `0`）。
- `orderable` 是**布尔**（CSV 里才是 `0`/`1`）。
- 逐行解析，**坏行跳过并计入报告**，不要整文件失败。
- 不用美化缩进：一行一个对象，方便流式读大文件。

---

## 9. 旧版 SQLite 格式（只读兼容）

现有 `DatabaseImporter.importFromExternalDb` 读的就是这个：

```sql
SELECT barcode, name, price FROM products
```

- 表名固定 `products`；`price` 按 **TEXT** 存（`BigDecimalTypeConverter` 用 `toPlainString()`），
  但也可能是 REAL/NUMERIC —— **一律按字符串读再转 `BigDecimal`**。
- 用 `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)` 打开，**只读**。
- 只认这三列，其余列忽略。
- v0 语义：`box_price` 空、`box_size` 0、`stock` 0、`orderable` 1，且**同样要做 §4.2 的 `0` 前缀合并**。

⚠ 现有的 `DatabaseImporter` 有两处要在改造时修掉：
1. 注释写着"清空当前数据库并导入"但**实际没有清空**；
2. 直接 `SQLiteDatabase.openDatabase` 一个来源不明的文件是有风险的
   （SQLite 文件可以带 `ATTACH`、触发器、虚拟表）。改造后走 SAF 取流、
   拷到 `cacheDir` 再只读打开，并且**只执行上面那一条 SELECT**。

---

## 10. 将来扩展：订单

本文只覆盖商品。订单的交换格式**尚未定义**，需要时按同样的思路加一个
`format: "s1-orders"`。要注意的约束比商品多得多：

- 订单一旦写进服务器的字节文件就**只能等长覆写**，改不了长度；
- 补传订单会触发服务器按 `content.items` **扣减库存**，没有开关能关；
- 补传的订单可能落进 `createdAt` 之前的月份，而那些月份不在同步枚举范围内
  （客户端要用 `enumFloor = min(createdAt, 本地最早订单月, 2020-01)` 兜住）。

所以订单的上线走 App 内的"上线向导"，**不走**通用导入文件。

---

## 11. 版本演进规则

- 加**可选列**不算破坏性变更：导入按列名取值，未知列忽略，缺失的可选列按缺省值。
- 加**必填列**、改列名、改语义 → `version` 加 1，并保留对旧版本的读取能力。
- **永不删除**对 v0（`barcode,name,price`）与旧版 SQLite 的读取支持——
  已经发出去的导出文件收不回来。
