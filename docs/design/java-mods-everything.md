# ม็อด Java ทุกตัว: เกมจริงเป็นหลัก ส่วน Rust เร่งเฉพาะที่พิสูจน์ได้ว่าปลอดภัย

สถานะ: แบบร่าง (2026-11-25) ยังไม่มีโค้ด ต่อยอดจาก `forge/ari` ของ sunrise-org/sunrise (MIT) ที่ดึงมาไว้เป็น `sunrise/forge-ari`

## เป้าหมาย

- **ใช้ได้ทุกม็อดเหมือนเกมจริง:** NeoForge, Fabric, Quilt, Forge ทุกเวอร์ชันที่ผู้เล่นมีเกมอยู่
- **เบาที่สุด:** ถ้าไม่ลงม็อดเลย ไม่เริ่ม JVM และเร็วเท่า Horizon ตอนนี้ ถ้าลงม็อด ต้องไม่หนักกว่าเกม Java แบบเดียวกัน และเร็วขึ้นได้เท่าที่ม็อดเปิดโอกาสให้
- **ไม่มีข้อจำกัดทางเทคนิค:** ที่เหลือมีแค่ผู้เล่นต้องมี Minecraft ของตัวเอง

วัดผลจาก: อัตราม็อดที่บูตผ่าน เทียบกับ loader จริง (เป้า = เท่ากัน), event trace ที่ต่างจาก loader จริง (เป้า = 0), และ MSPT, FPS, RAM เทียบกับ Java

## หลักการ 4 ข้อ

1. **Java คือความจริง:** เกม Java ตัวจริงพร้อม loader และม็อดรันอยู่เสมอ ความถูกต้องจึงมาจากโครงสร้าง ไม่ต้องไล่ตามม็อดทีละตัว
2. **Rust แทนได้เฉพาะเมื่อมีหลักฐาน:** method ไหนที่ม็อดไม่ได้แตะ (พิสูจน์จาก bytecode) และ Horizon มีของที่ผ่าน parity แล้ว จึงให้ Rust รัน
3. **โลกมีชุดเดียว:** ข้อมูลที่ใช้บ่อยอยู่ในหน่วยความจำที่ Rust เป็นเจ้าของ และ Java มองเห็นตรง ๆ ไม่มีการคัดลอกและ sync
4. **แกนกลางไม่รู้จัก loader และเวอร์ชันเป็นแค่ข้อมูล:** เพิ่ม loader หรือเวอร์ชันใหม่ได้โดยไม่ต้องแก้แกนกลาง

## ภาพรวม

```
 launcher: อ่าน jar → รู้ loader + เวอร์ชัน → โหลด loader จาก maven ทางการ, JDK, ใช้เกมของผู้เล่น
    │
    ▼
 ┌──────────────── โปรเซสเดียว ─────────────────────────────────────────────┐
 │  JVM ฝังในเกม                                                           │
 │   loader จริงบูตด้วย main ของตัวเอง (FML / Knot / Quilt / Forge)          │
 │   + ม็อดทุกตัว + mixin ทั้งหมด                                           │
 │        │  ทุกคลาสที่โหลดผ่าน define                                       │
 │        ▼                                                                │
 │   Horizon Agent (-javaagent): เห็น bytecode สุดท้ายหลัง mixin แล้ว       │
 │        │  เทียบกับ vanilla → แผนการแทนที่                                 │
 │        ▼                                                                │
 │   method ที่ไม่ถูกแตะ → ต้น method กระโดดไป Rust (FFM downcall)          │
 │   method ที่ถูกแตะ    → Java รันตามปกติ                                  │
 │ ─────────────────────────────────────────────────────────────────────── │
 │  Rust (Horizon): ตัวเร่ง (accelerator) + หน่วยความจำโลก + renderer wgpu   │
 └─────────────────────────────────────────────────────────────────────────┘
```

## ชิ้นส่วน

### A. Horizon Agent: จุดเดียวที่ใช้ได้กับทุก loader

ไม่ต้องใช้ `ClassProcessor` ของ FML (ตามที่ `BridgeProcessor` ใน forge/ari ใช้อยู่) ใช้ **Java agent** (`java.lang.instrument`) แทน เพราะ:
- **ใช้ได้ทุก loader:** `ClassFileTransformer` ของ agent ถูกเรียกตอน `defineClass` ไม่ว่า loader จะเป็นตัวไหน
- **เห็น bytecode ที่ถูกแก้เสร็จแล้ว:** ทำงานหลัง transform ของ loader ทั้งหมด จึงเห็นผลของ mixin, access transformer, coremod และ patch ของ loader ครบในครั้งเดียว ไม่สนว่าถูกแก้ด้วยวิธีไหน

### B. แผนการแทนที่ (Substitution Plan)

ทุก method ของ vanilla จะได้ 1 ใน 3 สถานะ ตัดสินจาก hash ของ bytecode สุดท้าย:

| สถานะ | เงื่อนไข | ใครรัน |
|---|---|---|
| `RUST` | bytecode = vanilla เดิม | Rust |
| `RUST_WITH_MOMENTS` | bytecode = vanilla + patch ของ loader ที่รู้จัก (เช่น NeoForge ยิง event) | Rust และยิง moment ให้ loader ตามตาราง |
| `JAVA` | ต่างจากทั้งสองแบบ (ม็อดแตะ) | Java ตัวจริง |

- **ตรวจทั้งสาย ไม่ใช่แค่ method เดียว:** ชุดหนึ่งปลอดภัยก็ต่อเมื่อ method ทุกตัวที่ชุดนั้นเรียกต่อไม่ถูกแก้ และทุก virtual call ในชุดไม่มีคลาสของม็อดมา override (ตรวจด้วย class hierarchy ของแพ็กนั้น) ถ้ามี จุดนั้นต้องให้ Rust ถาม Java แทนการเดา เช่น ระบบไฟถามความสว่างของบล็อกม็อดจาก `getLightEmission` ของ Java
- **แทนที่เป็นชุด (accelerator):** ระบบไฟ, ทำ mesh ของ chunk, noise ของ worldgen, หาเส้นทาง, ชน, redstone ฯลฯ ชุดหนึ่งจะใช้ Rust ได้ก็ต่อเมื่อทุก method ในชุดผ่าน ตัวจับคู่ method กับ Rust คือ port ledger (`tools/port_ledger.py`)
- **virtual call ไปหาคลาสของม็อด** (เช่น `onPlace` ของบล็อกม็อด): Rust ส่งกลับไป Java เฉพาะ receiver นั้น เหมือน `if` ของ bridge ใน forge/ari
- **มีรายงานต่อแพ็ก:** ชุดไหนเร่งได้ ชุดไหนไม่ได้ เพราะม็อดตัวไหน
- **ตาราง moment สร้างอัตโนมัติจาก hook catalog:** ใช้ชื่อ NeoForge และ key ของ Fabric ที่มีอยู่ใน [fabric-ledger.md](../fabric-ledger.md)

### C. หน่วยความจำโลกชุดเดียว

- **ข้อมูลบล็อกใช้รูปแบบเดียวกันอยู่แล้ว:** `horizon-core/src/paletted.rs` เก็บเป็น `u64` แบบ bit packing เดียวกับ `SimpleBitStorage` ของ vanilla agent จึงเปลี่ยน storage ของ Java ให้เป็น `MemorySegment` (FFM, JDK 22+) ที่ชี้หน่วยความจำของ Rust ได้
- **ใช้แบบเดียวกันกับ:** ไฟ (`DataLayer`), heightmap, biome
- **entity เป็น object ของ Java ตามเดิม** (เปลี่ยนแปลงได้หลากหลายเกินไป) accelerator ที่ต้องใช้ข้อมูล entity อ่านผ่าน view
- **ลำดับการเร่ง:** เร่งชุดที่ทำงานกับข้อมูลร่วมก่อน คือไฟ, mesh ของ chunk, worldgen และการชน ซึ่งเป็นส่วนที่หนักที่สุดด้วย ส่วนที่ต้องใช้ entity เป็นหลัก เช่น AI ของ mob ทำทีหลัง
- **ลำดับการทำงานเหมือน vanilla:** tick ของแต่ละ level ใช้ thread เดียว ส่วนงานขนานของ Rust ทำบน snapshot (copy-on-write ต่อ section)

### D. การวาดภาพ

- **Java client ตัวจริงรันอยู่:** วาดผ่าน backend ที่ Horizon ทำด้วย wgpu โดยทำตาม interface ของ Blaze3D (ต้องเช็กว่า 26.3 แยกชั้นนี้ไว้ครบแค่ไหน)
- **ส่วนที่ใช้งานหนักเป็น accelerator:** เช่นการทำ mesh ของ chunk และการวาด terrain ถ้าม็อดอย่าง Sodium แตะส่วนนั้น ก็กลับไปใช้ทาง Java ซึ่งยังทำงานได้

### E. รอยต่อ Rust ↔ Java

- **ใช้ FFM downcall/upcall แทน JNI ที่ทำได้:** เร็วกว่าใน JDK 22+
- **รวมการข้ามเป็นชุดต่อ tick** (`bulk.rs` ใน forge/ari)
- **moment ที่ไม่มีใครฟังไม่ข้ามรอยต่อเลย**
- **exception ของม็อดจับไว้ที่รอยต่อ:** มี watchdog เซฟโลกก่อนถ้า JVM ล่ม

### F. อบแพ็ก (pack bake)

- **วิเคราะห์ครั้งเดียวต่อแพ็ก:** fingerprint จาก jar ทุกตัว, config และเวอร์ชันของ loader เหมือน cache ของ forge/ari
- **เก็บผลไว้ใช้ซ้ำ:** แผนการแทนที่, คลาสที่ mixin แก้เสร็จแล้ว และ AOT cache ของ JDK 25 (Project Leyden: JEP 483 และ JEP 515)
- **ผล:** ครั้งต่อไปบูตเร็วขึ้นและอุ่นเครื่องน้อยลง ถ้าแพ็กเปลี่ยนก็อบใหม่

### G. เวอร์ชันเป็นข้อมูล

- **ต่อเวอร์ชันเก็บแค่สองอย่าง:** hash ของ bytecode vanilla และรายการ accelerator ที่มีให้เวอร์ชันนั้น
- **เวอร์ชันที่ยังไม่มี accelerator ก็ยังเล่นได้:** ทุกอย่างรัน Java ความเร็วเท่าเกมจริง แล้วค่อยเร็วขึ้นตามที่พอร์ตเพิ่ม ([version-ports.md](../version-ports.md))

## ข้อจำกัดที่เหลือ

| เรื่อง | ผล |
|---|---|
| ต้องมีเกมของผู้เล่นเอง | เรื่องกฎหมาย ไม่ใช่เทคนิค |
| แพ็กที่ม็อดแตะทุกชุด | ทำงานได้ครบ แต่ความเร็วใกล้ Java |
| JVM เอาคลาสออกไม่ได้ | เปลี่ยนม็อดต้องรีสตาร์ท เหมือนเกมจริง |
| interface วาดภาพของ 26.3 | ต้องพิสูจน์ใน P3 |

## ลำดับพิสูจน์ (ของเล็กก่อน)

1. ✅ **P1 Agent + diff ของ bytecode:** บูต Fabric 26.3 และ NeoForge 26.3 จริงพร้อมม็อดตัวอย่างใน JVM ธรรมดา แล้วให้ agent ออกรายงานว่าม็อดแตะ method ไหน ยังไม่ต้องใช้ Horizon เครื่องนี้มี Fabric 26.3 อยู่แล้ว
2. ✅ **P2 หน่วยความจำร่วม:** `PalettedContainer` ของ Java บนหน่วยความจำของ Rust เซฟแล้วได้ผลตรงกับ vanilla ทุก byte
3. **P3 วาดภาพ:** หน้า title ของ Java client จริงวาดผ่าน backend wgpu
4. **P4 accelerator ตัวแรก:** ระบบไฟ เปิดเมื่อแผนบอกว่าปลอดภัย และผ่าน parity harness เดิม

## ผล P1 (2026-11-25)

ตัวทดลอง: `python tools/loader_probe.py` (Agent อยู่ที่ `mods/horizon/src/loader-probe/`) รัน loader จริงสองรอบ คือไม่ลงม็อดกับลงม็อด แล้วทำรายงาน 3 ส่วน

1. **jar ของ loader เทียบกับ vanilla:** สิ่งที่ loader แก้ไว้ตอนติดตั้ง
2. **สิ่งที่ loader แก้ตอนโหลด:** mixin, access transformer และ coremod ของ loader เอง เทียบกับ jar บนดิสก์ (ตัวทดลองอ่านคลาสจาก code source ของแต่ละคลาส)
3. **สิ่งที่ม็อดแก้เพิ่ม:** เทียบกับ loader ตัวเดียวกันที่ไม่ลงม็อด

| Loader | ม็อด | คลาสที่โหลดได้ | ม็อดแก้ code | ม็อดแก้สิทธิ์ | ระบุที่มาไม่ได้ |
|---|---:|---:|---:|---:|---:|
| Fabric 26.3 (Loader 0.19.5) | 22 | 11,383 / 11,383 | 2,085 | 902 | 0 |
| NeoForge 26.3.0.118 | 13 | 11,383 / 11,383 | 779 | 106 | 0 |
| Forge 26.3-66.1.9 | 11 | 11,383 / 11,383 | 171 | 462 | 0 |
| Quilt 0.20.0-beta.9 | – | บูตไม่ได้ (ดูด้านล่าง) | – | – | – |

ตัวเลข "แก้ code" รวมแถว `<class>` ด้วย คือ supertype, interface, field และ annotation ของคลาส ซึ่ง mixin เปลี่ยนได้โดยไม่ต้องแตะ method ใดเลย

### ระบบที่ว่างให้ Rust เร่งได้ (ม็อดไม่แตะ)

| ระบบ | Fabric | NeoForge | Forge |
|---|---|---|---|
| ระบบไฟ | 3 (Sodium Extra, C2ME) | **0** | **0** |
| redstone | **0** | 2 (Lithium) | **0** |
| network | 44 (Fabric API, ViaFabricPlus) | 1 (Xaero) | 5 (Iceberg, GeckoLib, Xaero) |
| worldgen | 145 (C2ME 118) | 12 (Lithium, Sodium, AE2) | 17 (TerraBlender 15) |

ม็อดที่แตะระบบหนักส่วนใหญ่เป็นม็อดเร่งความเร็ว (C2ME, Lithium, Sodium) ซึ่ง Horizon จะทำแทนอยู่แล้ว ส่วนม็อดเนื้อหาอย่าง AE2 และ Biomes O' Plenty แทบไม่แตะ method ของเกม

### วิธีระบุที่มา เรียงจากแน่นอนที่สุด

1. mixin ที่ merge เข้า method (`@MixinMerged`) หรือ method ใช้ member ที่ mixin ประกาศ (ใช้ระบุค่าเริ่มต้นของ field ใน `<init>`/`<clinit>`)
2. สำหรับแถว `<class>`: mixin ที่ประกาศ field หรือเพิ่ม interface, accessor mixin (ตัว mixin เองกลายเป็น interface ของคลาส) และ interface ที่ม็อดฉีดเข้าไป (เช่น `FabricTooltipFlag` ของ Fabric API) ส่วน interface ของ loader เอง (เช่น `IKeyMappingExtension` ของ NeoForge) ไม่นับเป็นหลักฐาน
3. access widener (Fabric, Quilt) หรือ access transformer (Forge, NeoForge) ของม็อด ส่วน `*` และ `*()` นับเป็นทั้งคลาส และ Mixin เองก็ขยายสิทธิ์ได้ตามที่ mixin ประกาศ
4. coremod ของม็อด (`META-INF/coremods.json` → JavaScript) ในแพ็ก Forge นี้ Xaero's Minimap มี coremod ที่แก้ `ToggleKeyMapping`
5. mixin ทุกตัวบนคลาสมาจากม็อดเดียว (เช่น `Raider.<clinit>` ของ Lithium)
6. method ที่ loader เพิ่มเอง (เช่น `create(...)` ของ enum ที่ระบบขยาย enum ของ Forge เติมให้)

mixin อ่านจากไฟล์ mixin config และ `@Mixin` ในตัว jar ของม็อดเอง รวมถึง jar ที่ซ้อนอยู่ข้างใน (`META-INF/jars`, jarjar) จึงใช้ได้ทุก loader เพราะทุกตัวใช้ SpongePowered Mixin

### NeoForge: jar ถูก compile ใหม่ทั้งตัว

- **jar ของเกมที่ NeoForge สร้าง (`minecraft-client-patched`) คือ vanilla ที่ decompile แล้ว compile กลับ บวก source patch** bytecode จึงต่างจาก vanilla ถึง 86,812 method ทั้งที่ NeoForge แก้จริงไม่ถึง 3,000 ตัว
- **เทียบด้วย "สิ่งที่ method อ้างถึง":** ใช้เซตของการเรียก method, field, type และ string ที่ method ใช้ โดยพับ lambda ที่มันสร้างเข้ามารวมด้วย และตัดสิ่งที่การ compile ใหม่เปลี่ยนเองทิ้ง ได้แก่ `Objects.requireNonNull`, `MatchException`, ตัวเลขคงที่, ค่าที่จับมาใน anonymous class (`val$x`, `this$0`) และเลขของ lambda, anonymous class, `access$NNN` ส่วน anonymous class ที่ถูกเรียงเลขใหม่จับคู่กันด้วยรูปร่างของคลาสแทนเลข
- **ตัดสินระดับ method ด้วย source patch ของ NeoForge (`--loader-patches <folder>`):** jar ถูก compile พร้อมเลขบรรทัด เลยจับคู่บรรทัดที่ patch แก้กับช่วงบรรทัดของแต่ละ method (รวม lambda ที่มันสร้าง) ได้ตรงตัว
  - patch ที่แก้แค่ค่าคงที่หรือเครื่องหมายเปรียบเทียบก็นับ เช่น `PathNavigation` ที่แก้ MC-94054 (`<` เป็น `<=`) ซึ่งการเทียบสิ่งที่อ้างถึงอย่างเดียวมองไม่เห็น
  - patch ที่แก้นอก method (annotation, field, generic signature) นับเป็นแถว `<class>`
  - รูปแบบ patch ของ NeoForge ไม่ใส่เลขบรรทัดฝั่งใหม่ (`@@ -250,11 +_,14 @@`) ตัวทดลองคำนวณจากผลต่างสะสมของ hunk ก่อนหน้า
- **ผล (patch ของ commit `84d0790633` บน branch `26.3.x` ซึ่งตรงกับ 26.3.0.118 พอดี เพราะอยู่ห่างจาก tag `26.3.0` 118 commit):**
  - เจอการแก้ใน **850/850 (100%)** ของคลาสที่มี patch
  - ไม่มีคลาสที่ไม่มี patch แต่มี method เพิ่มหรือหาย
  - ที่เจอเพิ่มโดยไม่มีไฟล์ patch คือ 228 คลาสที่เปลี่ยนแค่สิทธิ์ ซึ่งมาจาก access transformer ที่ตัวติดตั้งของ NeoForge ใส่ลงใน jar (เป็นการแก้จริงของ loader)
  - ตอนโหลด NeoForge แก้เพิ่มอีก 65 method และเปลี่ยนสิทธิ์ 82 ตัว
- **ถ้าไม่มีไฟล์ patch:** ตัวทดลองยังตัดสินด้วยสิ่งที่อ้างถึงได้ แต่จะเหลือเศษจากการ compile ใหม่ราว 4% ซึ่งอยู่ฝั่งปลอดภัย (ทำให้ Java รันเกินจำเป็น ไม่ทำให้ม็อดพัง)
- **ตัดออกจากชุดทดสอบ:** Iris NeoForge 26.3 (1.11.7) ใช้ร่วมกับ Sodium รุ่นไหนก็ไม่ได้ จึงเอาออก และใช้ Sodium 0.9.3 แทน 0.9.4-beta.2

### Forge

- **บูตด้วย `net.minecraftforge.bootstrap.ForgeBootstrap`:** jar ของ Forge (`forge-26.3-66.1.9-client.jar`) เก็บคลาสของเกมทั้งชุด คลาสที่ patch ถูก compile ใหม่ (16,004 method เป็นแค่ compile ใหม่) และ Forge แก้เอง 1,624 method ในไฟล์ jar กับอีก 58 method ตอนโหลด
- **ม็อดแก้เพิ่ม 171 ระบุที่มาได้ครบ** ระบบไฟและ redstone ไม่ถูกแตะ

### Quilt Loader 0.20.0-beta.9 (ปล่อยไว้ รอ Quilt ออกรุ่นใหม่)

- **บูต 26.3 ไม่ได้ เป็นข้อจำกัดของ Quilt เอง ไม่ใช่ของตัวทดลอง:** Quilt ใช้ ASM 9.5 ซึ่งอ่านคลาสของ Java 25 (major version 69) ไม่ได้ จึงล้มใน `GameTransformer.locateEntrypoints` ก่อนโหลดม็อดใด ๆ และ QSL ก็ยังไม่มีรุ่นสำหรับ 26.3
- **พอ Quilt ออกรุ่นที่รองรับ 26.3 แล้ว ให้รันคำสั่งเดิมซ้ำ** ตัวทดลองไม่ต้องแก้

### ข้อ D (การวาดภาพ) ได้หลักฐานแล้ว

ใน jar ของ 26.3 ระบบวาดภาพแยกเป็น API กลาง (`com/mojang/renderpearl/api`: `GpuBackend`, `GpuDevice`, `CommandEncoder`) และมี backend สองตัวคือ `backend/opengl` กับ `backend/vulkan` backend ตัวที่สามที่ทำด้วย wgpu จึงมีที่เสียบรออยู่แล้ว

### ทำซ้ำ

```sh
python tools/loader_probe.py                                   # Fabric: โปรไฟล์และม็อดใน launcher
python tools/loader_probe.py --minecraft DIR --profile neoforge-26.3.0.118 --mods MODS --loader-patches PATCHES
python tools/loader_probe.py --minecraft DIR --profile 26.3-forge-66.1.9 --mods MODS
python tools/loader_probe.py --report-only                     # ทำรายงานใหม่จากรอบล่าสุด
python -m unittest tests.test_loader_probe                     # 8 เทสต์
```

ทุกรอบรันในโฟลเดอร์ชั่วคราวใต้ `.local/loader-probe/` (git ไม่เก็บ) ไม่อ่านและไม่เขียนเซฟ, การตั้งค่า หรือ config ของผู้เล่น

## ผล P2 (2026-11-25): โค้ด vanilla ทำงานบนหน่วยความจำของ Rust

ตัวทดลอง: `python tools/shared_memory_probe.py`

- **ฝั่ง Rust:** crate `crates/horizon-shm-probe` (cdylib) จองหน่วยความจำของ storage และอ่านเขียนด้วยโค้ดของ `horizon-core` เอง (`PackedStorage`, `PalettedContainer`)
- **ฝั่ง Java:** `mods/horizon/src/shared-memory-probe/` ประกอบด้วย
  - `RustBitStorage` คือ `SimpleBitStorage` ที่ทุกการอ่านเขียนไปลงหน่วยความจำของ Rust ตรง ๆ ผ่าน FFM (`java.lang.foreign`) ไม่มีการคัดลอก
  - Agent เปลี่ยนทุก `new SimpleBitStorage(...)` ในโค้ดเกมเป็น `RustBitStorage.create(...)` (ทำงานบน bytecode จึงใช้ได้ทุก loader)

### วิธีทดสอบ

รันงานชุดเดียวกันสองรอบ รอบแรกบน storage ของ vanilla รอบสองบน storage ของ Rust แล้วเทียบผล

- **96 section:** แต่ละ section มีจำนวนสถานะบล็อกต่างกัน (1 ถึง 5,000) ครอบคลุม palette ทุกแบบ ได้แก่ single, linear, hash map และ global แต่ละ section ทำ `getAndSet` 9,000 ครั้ง
- **สิ่งที่เทียบ:** ค่าทุก cell, byte ที่ส่งผ่าน network, การอ่านกลับจาก network, `copy()`, ข้อมูลตอนเซฟ (codec ของ `PalettedContainer`) และลำดับค่าที่ `getAndSet` คืน

### ผล

- **ตรงกันทั้ง 96/96 section ทุกตัวที่เทียบ**
- **Rust อ่านหน่วยความจำเดียวกันด้วย `horizon-core` ตรวจ 352 ครั้ง ผิด 0 ครั้ง:**
  - ค่าทุกตัวใน storage ตรงกัน
  - `PalettedContainer` ของ `horizon-core` ที่สร้างจาก word เดียวกันกับ palette เดียวกัน อ่านทุก cell ได้ตรง
  - Rust เขียน cell ลงไปแล้ว Java อ่านเห็นทันที
- **`RustBitStorage` เทียบกับ `SimpleBitStorage` ทีละคำสั่ง:** bit 1–32 × ขนาด 1, 37, 64, 4096 รวม 128 กรณี ได้ long ตรงกันทุก bit รวมถึง padding ที่ `fill` ใส่ไว้
- **ความเร็ว** (เครื่องของผู้พัฒนา ns ต่อการทำงานหนึ่งครั้ง 3 รอบ):

| | vanilla | Rust |
|---|---:|---:|
| storage ตรง ๆ (`getAndSet` + `get`) | 9.0 / 11.8 / 9.2 | 9.7 / 11.5 / 11.2 |
| ผ่าน `PalettedContainer` | 104 / 94 / 99 | 114 / 114 / 120 |

  storage เท่ากันในระดับ noise ระหว่างรอบ (ใช้ VarHandle ของ array view และการหารแบบคูณ-shift แบบเดียวกับ vanilla) ส่วนผ่าน container ช้ากว่า 10–20% ยังต้องหาสาเหตุ

### สิ่งที่เจอระหว่างทาง

- **โค้ด vanilla เขียนลงใน array ที่ `getRaw()` คืนมาโดยตรง:** `PalettedContainer.read` (อ่าน section จาก network) และ `Heightmap.setRawData` เพราะ array บน heap ชี้หน่วยความจำนอก heap ไม่ได้ Agent จึงเปลี่ยนทุก `getRaw()` เป็น `RustBitStorage.raw(...)` ซึ่งให้สำเนาไป และตอนจบ method ที่เรียกมันจะเขียนส่วนที่ถูกแก้กลับลงหน่วยความจำของ Rust (`flush`)
- **ในทั้ง jar มี 7 จุดที่เรียก `getRaw()` ของ storage:** ข้อสรุปนี้จึงมาจากการตรวจทุกจุด ไม่ใช่การเดา

### ที่ยังไม่ได้พิสูจน์ใน P2

- **ยังไม่ได้ลองใน Horizon จริง:** ตอนนี้ Rust เป็นเจ้าของหน่วยความจำ แต่ยังไม่ได้ให้ `World` ของ Horizon ใช้หน่วยความจำนั้นเป็นที่เก็บ chunk จริง อีกทั้ง `PackedStorage` ของ `horizon-core` ยังถือ `Vec` ของตัวเอง ต้องมีรุ่นที่ยืม word ได้ (ตอนนี้ฝั่ง Rust อ่านผ่านสำเนา ส่วนฝั่ง Java ไม่มีการคัดลอก)
- **ยังไม่ได้ทำ:** ระบบไฟ (`DataLayer`), heightmap ในโลกจริง และการทำงานหลาย thread
- **ยังไม่ได้ตรวจ:** `cargo fmt` และ `clippy` เพราะเครื่องนี้ไม่ได้ติดตั้ง rustfmt/clippy แต่ `cargo test -p horizon-shm-probe` ผ่าน

## เอาอะไรจาก forge/ari มาใช้

host JNI (`horizon-java-host`), views, bridges, routes (`routes.rs`: Edge/Body), การ export facts และ cache เนื้อหา, harness ที่เทียบกับ loader จริง สิ่งที่เปลี่ยนคือฝั่งที่เป็นหลัก (Java) และจุดที่เข้าไปแทรก (agent แทน `ClassProcessor`)
