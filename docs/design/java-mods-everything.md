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
- **ลำดับการทำงานเหมือน vanilla:** tick ของแต่ละ level ใช้ thread เดียว Rust แตะข้อมูลได้เฉพาะบน thread ที่เป็นเจ้าของข้อมูลนั้น (บล็อกอยู่บน thread ของ level, แสงอยู่บน thread แสง) ซึ่งพิสูจน์แล้วใน P2 ส่วนงานขนานของ Rust ทำบน snapshot (copy-on-write ต่อ section)

### D. การวาดภาพ

- **Java client ตัวจริงรันอยู่:** วาดผ่าน backend ที่ Horizon ทำด้วย wgpu โดยทำตาม interface backend ของ renderpearl ใน 26.3 (ทำแล้วใน P3)
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
| interface วาดภาพของ 26.3 | พิสูจน์แล้วใน P3 (backend ตัวที่สามเสียบได้จริง) |

## ลำดับพิสูจน์ (ของเล็กก่อน)

1. ✅ **P1 Agent + diff ของ bytecode:** บูต Fabric 26.3 และ NeoForge 26.3 จริงพร้อมม็อดตัวอย่างใน JVM ธรรมดา แล้วให้ agent ออกรายงานว่าม็อดแตะ method ไหน ยังไม่ต้องใช้ Horizon เครื่องนี้มี Fabric 26.3 อยู่แล้ว
2. ✅ **P2 หน่วยความจำร่วม:** บล็อก แสง และ heightmap ของเกม Java ตัวจริงอยู่บนหน่วยความจำของ Rust โลกจริงที่แก้แล้วให้แสงแล้วเซฟออกมาตรงกับ vanilla ทุก chunk และ `LevelAccess` ของ Horizon อ่านเขียนโลกที่กำลังรันอยู่ได้ตรง
3. ✅ **P3 วาดภาพ:** Java client จริงวาดผ่าน backend wgpu ของ Horizon ทั้งหน้า title (ตรง OpenGL ทุก pixel) และในโลก (ต่างจาก OpenGL น้อยกว่าที่ Vulkan ของ Mojang ต่าง) เร็วกว่า Vulkan ของ Mojang เล็กน้อย และถอยไปใช้ backend ของเกมเองเมื่อ wgpu ใช้ไม่ได้
4. ✅ **P4 accelerator ตัวแรก:** การกระจายแสงของเกมจริงรันใน Rust บนข้อมูลแสงของเกมเอง ผลตรง vanilla ทุก chunk ทั้งตอนแก้บล็อกและตอนให้แสงทั้งโลกใหม่ ฝั่ง client ภาพตรงด้วย และเร็วขึ้นราว 1.6 เท่า เปิดเฉพาะเมื่อคลาสระบบไฟทุกตัวเป็น bytecode ของ vanilla

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

ตัวทดลอง: `python tools/shared_memory_probe.py` (ใส่ `--horizon-resources DIR` ที่มี `facts.json` ของ Horizon เพื่อตรวจ registry ด้วย)

- **ฝั่ง Rust:** crate `crates/horizon-shm-probe` (cdylib) จองหน่วยความจำ และอ่านเขียนด้วยโค้ดของ Horizon เอง
  - `PackedStorage` ของ `horizon-core` ยืม word ได้แล้ว (`PackedStorage<&[u64]>` สำหรับอ่าน, `PackedStorage<&mut [u64]>` สำหรับเขียน) โดยไม่คัดลอก ค่าเริ่มต้นยังเป็น `Vec` เหมือนเดิม โค้ดที่เรียกใช้อยู่จึงไม่ต้องแก้
  - `JavaLevel` (`src/level.rs`) คือ `LevelAccess` ของ `horizon-world` ซึ่งเป็น interface โลกที่โค้ด Horizon ใช้ ทำงานบนโลก Java ที่กำลังรันอยู่ ทั้งบล็อก แสง และ heightmap อ่านจากหน่วยความจำของเกมตรง ๆ
- **ฝั่ง Java:** `mods/horizon/src/shared-memory-probe/`
  - `RustBitStorage` คือ `SimpleBitStorage` (บล็อก, biome, heightmap) และ `RustDataLayer` คือ `DataLayer` (แสง) ทุกการอ่านเขียนลงหน่วยความจำของ Rust ตรง ๆ ผ่าน FFM
  - Agent เปลี่ยนทุก `new SimpleBitStorage(...)`, `new DataLayer(...)` และ method reference `DataLayer::new` ในโค้ดเกมไปเป็น factory ของเรา (ทำงานบน bytecode จึงใช้ได้ทุก loader)
  - `World` คือตัวทดลองบนเซิร์ฟเวอร์ GameTest ของ vanilla ซึ่งเป็นเซิร์ฟเวอร์เกมจริงที่ไม่ต้องมีผู้เล่นและไม่ต้องยอมรับ EULA

### 1. ตัวทดลอง storage

รันงานชุดเดียวกันบนหน่วยความจำของ vanilla และของ Rust แล้วเทียบกัน

- **96 section:** จำนวนสถานะบล็อก 1 ถึง 5,000 ครอบคลุม palette ทุกแบบ ตรงกันทั้ง 96/96 ทั้งค่าทุก cell, byte ที่ส่งผ่าน network, การอ่านกลับ, `copy()` และข้อมูลตอนเซฟ
- **Rust อ่านหน่วยความจำเดียวกันด้วยโค้ดของ `horizon-core`:** ตรวจ 352 ครั้ง ผิด 0 ครั้ง
- **`RustBitStorage` เทียบกับ `SimpleBitStorage` ทีละคำสั่ง:** 128 กรณี ได้ long ตรงกันทุก bit
- **`RustDataLayer` เทียบกับ `DataLayer`:** 96 กรณี ตรงกันทั้งระดับแสงทุกจุด, flag ทุกตัว, byte, ข้อความ และ copy และ Rust อ่านแสงจากหน่วยความจำได้ถูกทุก layer
- **8 thread พร้อมกัน:** แต่ละ thread มี container ของตัวเองตามกฎของเกม (หนึ่ง container ต่อหนึ่ง thread) Cleaner คืนหน่วยความจำไปพร้อมกัน ตรวจ 1,966,560 ครั้ง ผิด 0 ครั้ง

### 2. ตัวทดลองโลกจริง

ใช้เซิร์ฟเวอร์เกมจริงกับโลกแบบ normal ขนาด 144 chunk แช่ tick ไว้ (`tick freeze`) จึงไม่มี block tick, mob หรือ random tick มารบกวน แต่ระบบ chunk, แสง และ heightmap ยังทำงานตามปกติ

- **vanilla รันเทียบกับตัวเองไม่ได้:** ลองรันสร้างโลกสองรอบด้วย seed เดียวกัน chunk ต่างกัน 130/144 เพราะ worldgen หลาย thread วาง feature ที่ทับกันในลำดับต่างกันทุกครั้ง การทดลองจึงเริ่มจากเซฟเดียวกันทุกรอบ
- **เซฟเดียวกัน → แก้บล็อก 40,000 จุด (มีทั้งของที่ให้แสงและบล็อกทึบ) → รอแสงนิ่ง → เซฟ:**
  - เทียบหน่วยความจำของ vanilla กับของ Rust
  - ต่างกัน 0/144 chunk ทั้งบล็อก, biome, แสงบล็อก, แสงท้องฟ้า, heightmap และ NBT ที่เซฟ
  - vanilla เทียบกับตัวเองก็ได้ 0 เช่นกัน แสดงว่าวิธีทดสอบนี้เชื่อถือได้
- **สร้างโลกใหม่บนหน่วยความจำของ Rust (worldgen รันบน worker thread ของเกม) แล้วให้ vanilla โหลดเซฟนั้น:**
  - บล็อก, biome, แสง และ heightmap ต่าง 0 ทั้งใน 64 chunk ด้านในและที่ขอบ
  - NBT ที่เซฟซ้ำต่างกันเท่ากับที่ vanilla เซฟซ้ำโลกของตัวเอง คือ 63 chunk ด้านในและ 80 chunk ที่ขอบ ที่ต่างเพราะ tick ที่รออยู่นับจากนาฬิกาของแต่ละรอบ และ palette กลับมาในลำดับใหม่ ส่วนเนื้อหาบล็อกยังตรงกัน
  - ขอบ 2 วงไม่นับเป็นผ่านหรือไม่ผ่าน เพราะหลังโหลดเซฟ เพื่อนบ้านยังสร้างและให้แสงต่อ ใน vanilla เองบางรอบก็ต่างตรงขอบ
- **Horizon บนโลกที่กำลังรันอยู่** (ทั้งรอบโหลดเซฟและรอบสร้างโลกใหม่):
  - section ที่ลงทะเบียน 3,321 อยู่บนหน่วยความจำ Rust ทั้งหมด (ไม่มี section ที่ยังใช้ของ Java), light layer 7,488 และ heightmap 576 ก็อยู่บน Rust ทั้งหมดเช่นกัน
  - `LevelAccess` ของ Horizon อ่าน 144 chunk บน 8 thread ของ Rust ได้บล็อก, แสงทั้งสองชนิด และ heightmap ตรงกับที่เกมเห็น ผิด 0
  - Horizon เขียนบล็อกผ่าน `LevelAccess::set_raw` 500 ครั้ง เกมเห็นทันที ผิด 0
- **ID ของ registry Horizon คือ ID ของเกม:** ทั้ง 35,723 สถานะบล็อก ชื่อและ property ตรงกันหมด

### กฎของ thread (พิสูจน์แล้ว)

- Rust อ่านหรือเขียน section ได้เฉพาะตอนที่ไม่มี thread ของ Java เขียน section นั้นอยู่ ซึ่งเป็นกฎเดียวกับที่ `ThreadingDetector` ของ `PalettedContainer` บังคับอยู่แล้ว
- แสงถูกเขียนบน thread แสงของเกมเอง (`ConsecutiveExecutor` ของ `ThreadedLevelLightEngine`) การทดลองจึงส่งงานอ่านของ Rust เข้าไปเป็น task บน thread นั้น
  - รอบแรกที่อ่านจาก thread อื่น แสงต่างจาก Java 3 chunk เพราะเกมยังให้แสง chunk ขอบอยู่ระหว่างนั้น
  - พอย้ายเข้า thread แสงแล้วผิด 0
- accelerator ของจริงจะต้องรันบน thread เจ้าของข้อมูลแบบเดียวกัน

### ความเร็ว

เครื่องของผู้พัฒนา ns ต่อการทำงานหนึ่งครั้ง วัดหลังทำงานชุดเดียวกันในทั้งสองรอบ

| | vanilla | Rust |
|---|---:|---:|
| storage ตรง ๆ (`getAndSet` + `get`) | 11.1 / 13.0 / 9.6 / 11.8 | 11.3 / 12.2 / 9.7 / 11.0 |
| ผ่าน `PalettedContainer` | 118.5 / 109.6 / 112.4 / 115.5 | 116.5 / 124.0 / 100.0 / 122.2 |

- **เท่ากันในระดับ noise ของเครื่อง:** container เฉลี่ยราว 114 กับ 116 ns ส่วนช่วงห่าง 10–20% เดิมหายไปแล้ว
- **ที่แก้:**
  - อ่าน word ผ่าน segment เดียวที่ครอบทุก address (`Rust.ALL`) ซึ่งเป็นค่าคงที่ JIT จึงตัดการตรวจขอบเขตและอายุของ segment ทิ้งได้
  - ใช้ layout แบบไม่ตรวจ alignment
  - วัดผลหลังจากทั้งสองรอบทำงานชุดเดียวกันแล้ว (ก่อนหน้านี้รอบ Rust วัดหลังงานตรวจที่หนักกว่า)

### สิ่งที่เจอระหว่างทาง

- **โค้ด vanilla เขียนลงใน array ที่ `getRaw()` คืนมาโดยตรง:** ที่ `PalettedContainer.read` และ `Heightmap.setRawData` Agent จึงให้สำเนาไป แล้วเขียนส่วนที่ถูกแก้กลับเมื่อ method จบ (ทั้ง jar มี 7 จุดที่เรียก `getRaw()` ตรวจครบทุกจุด)
- **`DataLayer.getData()` ของ `RustDataLayer` คืนสำเนา:** ตรวจทุกจุดที่เรียกใน jar แล้ว ได้แก่ packet, การเซฟ chunk และการ copy ของ sky storage ไม่มีจุดไหนเขียนลง array นั้น
- **`SerializableChunkData` สร้าง `DataLayer` ผ่าน method reference (`DataLayer::new`):** Agent จึงเปลี่ยน handle ใน `invokedynamic` ด้วย
- **`getChunkNow` คืน null เมื่อเรียกจาก thread อื่น:** chunk ต้องรับมาบน server thread ก่อน
- **มีครั้งหนึ่งที่เซิร์ฟเวอร์ปิดเองตอนเริ่ม (exit 1) โดยไม่มี log:** สั่งเดิมซ้ำด้วยมือผ่าน และเปิดเซิร์ฟเวอร์ต่ออีก 21 ครั้งก็ไม่เกิดอีก ยังไม่รู้สาเหตุ

### ที่ยังเหลือ (เป็นงานของขั้นถัดไป ไม่ใช่ช่องโหว่ของ P2)

- **palette ส่งให้ Rust เป็นสำเนา:** มีไม่เกิน 2^bits id และ Java ขยายได้ทุกเมื่อ ส่วน word ของ storage และแสงไม่มีการคัดลอก
- **`PalettedContainer` ของ `horizon-core` (คนละตัวกับ `PackedStorage`) ยังถือ word ของตัวเอง:** `JavaLevel` จึงอ่าน section ผ่าน `PackedStorage` ที่ยืม word แทน
- **การให้ accelerator จริงรันบน thread เจ้าของข้อมูลเป็นงานของ P4 (ระบบไฟ):** ทำแล้ว ดูผล P4

## ผล P3 (2026-11-25): เกม Java ตัวจริงวาดภาพผ่าน wgpu ของ Horizon

ตัวทดลอง: `python tools/wgpu_backend_probe.py --backends opengl,vulkan,wgpu`

- **ฝั่ง Java:** `mods/horizon/src/wgpu-backend/`
  - `WgpuBackend` เป็น backend ตัวที่สามของเกม คู่กับ OpenGL และ Vulkan ของ Mojang
  - ชั้น frontend ของ renderpearl (ตรวจความถูกต้อง, คอมไพล์ GLSL เป็น SPIR-V) ทำงานตามเดิม เราเขียนเฉพาะชั้น backend 4 interface คือ `GpuDeviceBackend`, `CommandEncoderBackend`, `RenderPassBackend` และ `GpuSurfaceBackend`
  - Agent เปลี่ยน `PreferredGraphicsApi.getBackendsToTry` ตาม `-Dhz.render=wgpu|opengl|vulkan`
    - `wgpu`: ลอง wgpu ก่อน แล้วตามด้วยรายการเดิมของเกม เครื่องที่ wgpu ใช้ไม่ได้จึงยังเปิดเกมด้วย OpenGL หรือ Vulkan ของเกมได้
    - `opengl`, `vulkan` หรือมี `-Dhz.render.only=true`: ใช้ตัวนั้นตัวเดียว (ตัวทดลองใช้แบบนี้ เพื่อไม่ให้แอบไปวัด backend อื่น)
- **ฝั่ง Rust:** crate `crates/horizon-wgpu-backend` (cdylib) เรียกผ่าน FFM ใช้ wgpu 30 ตัวเดียวกับที่ Horizon ใช้
  - เลือก API ตัวแรกที่วาดลงหน้าต่างแบบเดียวกับของเกมได้ เรียงเป็น Vulkan, DX12, Metal, GL (`HZ_WGPU_BACKEND=vulkan|dx12|metal|gl` บังคับได้)
- **วิธีทดสอบ:**
  - เปิด client จริงในโฟลเดอร์เกมชั่วคราว (ไม่แตะเซฟหรือ options ของผู้เล่น) จนหน้า title วาดไปแล้ว 120 เฟรม
  - ให้โค้ด screenshot ของเกมเองอ่านเฟรมกลับมา (ทดสอบเส้นทางอ่านข้อมูลจาก GPU ไปด้วย) แล้วเทียบทีละ pixel
  - ตรึงหน้าจอให้นิ่ง: ปิดการหมุนของ panorama (`panoramaScrollSpeed:0`) และเอาข้อความ splash ที่สุ่มออก

### ผล (3 รอบ × 3 backend ผ่านทั้ง 9 ครั้ง, 854×480, AMD Radeon แบบ integrated)

| backend | ms ต่อเฟรม | ค้างนานสุดตอนโหลด | เทียบกับ OpenGL |
|---|---:|---:|---|
| OpenGL (Mojang) | 17.2–17.9 | 1.1–1.7 s | — |
| Vulkan (Mojang) | 16.7–18.0 | 1.3 s | ตรงทุก pixel |
| **wgpu (Horizon)** | **16.8–17.0** | 2.4–3.5 s | ต่างเฉลี่ย 0.45/255; 0.2% ของ pixel ต่างเกิน 64 ทั้งหมดอยู่ที่ขอบโลโก้ |

- **ความเร็วเท่ากัน:** ทั้งสามตัวถูก vsync จำกัดไว้ที่ราว 60 fps
- **ค้างตอนโหลดไม่ถึง 5 วินาที:** Windows จึงไม่ขึ้นว่า Not Responding
- **pipeline ทุกตัวที่เกมสร้างคอมไพล์ผ่าน:** ไม่มีตัวที่ถูกปฏิเสธ ไม่มี validation error และไม่มีฟีเจอร์ที่เกมบอกว่าเครื่องไม่รองรับ (รวม wireframe)
- **Rust test (`tests/draw.rs`):** วาดสามเหลี่ยมผ่าน API ทั้งเส้นแล้วเช็กว่าลงแถวเดียวกับ backend Vulkan และ culling เก็บหน้าเดียวกัน

### รูปแบบของเกมที่ต้องตามให้ตรง (วิธีทำ)

- **แถวของภาพ:** backend Vulkan ของ Mojang ใช้ viewport ปกติ แถว 0 ของ texture จึงอยู่ที่ clip-space y = -1 (เหมือน OpenGL) แล้วค่อยกลับภาพตอนส่งขึ้นจอ ส่วน wgpu วาง y = +1 ที่แถว 0
  - `shader.rs` จึงกลับเครื่องหมาย y ของ vertex shader ทุกตัวใน IR ของ naga
  - ทุก render target, การ copy และการอ่านกลับจึงได้แถวเดียวกับ backend Vulkan
  - การ blit ขึ้นจอกลับภาพคืน
  - front face เป็น clockwise เหมือน backend Vulkan (test ยืนยันแล้ว)
- **sampler รวม (`sampler2D`):** wgpu แยก texture กับ sampler `spirv.rs` จึงแยกใน SPIR-V
  - texture อยู่ที่ binding เดิม (frontend ใส่ index ของ uniform ไว้ให้) ส่วน sampler อยู่ที่ binding + 64
  - เกมคอมไพล์แบบไม่ optimize sampler จึงถูกส่งเป็นพารามิเตอร์ของฟังก์ชันด้วย พารามิเตอร์และการเรียกเหล่านี้ได้ sampler คู่กันไป
- **texel buffer (`isamplerBuffer`, เมฆ):** WebGPU ไม่มี `texel.rs` จึงเปลี่ยนเป็น storage buffer แบบอ่านอย่างเดียว แล้วแปลง `texelFetch` เป็นการอ่าน word, เลื่อน byte และขยาย sign
- **output แบบ array (`out vec4 coeff[N]`, ระบบโปร่งใส OIT):** WebGPU ไม่มี จึงแยกเป็น N output ที่ location ต่อกัน
- **uniform block:** เกมผูกแค่ไบต์ที่ใช้ (140 จาก block 144) Vulkan ยอม แต่ wgpu ไม่ยอมและทิ้งทั้งเฟรม จึงผูกเต็มแถว 16 byte
- **ลำดับคำสั่ง:** การเขียน buffer หรือ texture ถูกบันทึกเป็นการ copy ลง command encoder ตามลำดับที่เกมสั่ง ส่วน transient memory (เขียนครั้งเดียวต่อเฟรม) ส่งขึ้นตอน submit

### สิ่งที่เจอระหว่างทาง

- **เกมปิดเองแบบสุ่มโดยไม่มี crash report:**
  - เกิดตอนคอมไพล์ shader เพราะ native code กิน stack เกินขอบ จนแม้แต่ JVM ก็เขียน report ไม่ได้
  - ไฟล์เวอร์ชันของ launcher จึงใส่ `-XX:StackShadowPages=32` ไว้ ตัวทดลองใส่ตามแล้ว และ Rust ทำงาน shader ทั้งหมดบน thread ที่มี stack 64 MB
  - ก่อนแก้รอดราว 50% หลังแก้ผ่านทุกครั้ง
- **Rust ไม่เก็บ pointer ไปยังหน่วยความจำของ Java และไม่ยื่นหน่วยความจำของตัวเองให้ Java:** สำเนา CPU ที่เกม map เป็นของ Java แต่ละ call คัดลอกเฉพาะที่ต้องใช้ ตอนแรกให้ Java เขียนลงหน่วยความจำของ Rust แล้ว heap เสีย
- **`allocateCpu` ต้องคืนบล็อกที่อยู่จนเฟรมจบ:** เกมอาจเก็บไว้แค่ address เหมือนที่ backend อื่นยอมให้ทำ
- **อย่าให้ thread วาดภาพรอการคอมไพล์ pipeline:** การสร้าง pipeline (บน worker ของเกม) ใช้ device แยก ไม่ถือ lock เดียวกับเฟรม ก่อนแก้ หน้าต่างค้างจน Windows ขึ้นว่า Not Responding
- **Vulkan ของ wgpu บอกว่าไม่รองรับ surface ของหน้าต่าง Win32 บนไดรเวอร์ AMD เครื่องนี้:** จึงใช้ DX12 แทน
- **เครื่องมือดีบัก:** `HZ_WGPU_TRACE=1` (นับคำสั่งต่อเฟรม), `HZ_WGPU_DUMP=ไฟล์.ppm` กับ `HZ_WGPU_DUMP_FRAME` (บันทึกเฟรมที่ส่งขึ้นจอ)

### รอบที่สอง (2026-11-26): ของที่เหลือจากรอบแรก

ตัวทดลอง: `python tools/wgpu_backend_probe.py --world <โลกที่ P2 สร้าง> [--uncapped]`

- **ในโลก:** เปิดโลกที่ P2 สร้าง ตรึงกล้อง (`--view`) ตั้งเวลาเป็น 1000 หยุดนาฬิกา (`advance_time false`) และแช่ server
  - จับภาพหลังผ่านไปอย่างน้อย 600 เฟรม **และ** 30 วินาที (`--hold`) เพราะสีท้องฟ้าและหมอกค่อย ๆ ผสมตามเวลาจริง ไม่ใช่ตามจำนวนเฟรม
  - ถ้านับแค่เฟรม รอบที่ fps ต่างกันจะจับภาพกันคนละช่วงเวลา: OpenGL เทียบกับ OpenGL รอบอื่นยังต่างถึง 5.1
- **ผล (Vulkan ของ wgpu, ต่างเฉลี่ยต่อ channel จาก 255):**

| ภาพ | wgpu เทียบ OpenGL | Vulkan ของ Mojang เทียบ OpenGL |
|---|---|---|
| หน้า title | ตรงทุก pixel | ตรงทุก pixel |
| ในโลก | 0.026 (pixel ต่างเกิน 16 มี 1 จุด) | 0.060 (pixel ต่างเกิน 16 มี 1 จุด) |

- **ความเร็ว (`--uncapped`: ปิด vsync และไม่จำกัด fps, ในโลก):**

| backend | ms ต่อเฟรม |
|---|---:|
| OpenGL (Mojang) | 3.8–4.2 |
| Vulkan (Mojang) | 5.5 |
| wgpu ก่อนรอบนี้ | 10.6 |
| wgpu หลังรอบสอง | 6.0–6.8 |
| **wgpu หลังรอบสาม** | **3.85–3.88** (Vulkan ของ Mojang รอบเดียวกัน 3.89–3.99) |

#### ที่แก้

- **พื้นโลกดำบน Vulkan (DX12 ไม่เป็น):**
  - naga รับ parameter `in` ของ GLSL เป็น pointer ไปยังสำเนา local
  - บน Vulkan ฟังก์ชันที่ส่ง pointer ของ local ตัวเองต่อให้อีกฟังก์ชันจะอ่านได้ศูนย์ ทำให้ฟังก์ชันหมอก (fog) ของเกมคืนสีดำ
  - `shader.rs` (ขั้น 5) จึงเปลี่ยน pointer parameter ที่ฟังก์ชันแค่อ่าน ให้เป็นการส่งค่าตรง ๆ ซึ่งตรงกับความหมายของ GLSL อยู่แล้ว
  - มี test `nested_fog_functions_keep_the_color` ครอบไว้
- **แสงพระอาทิตย์ขึ้นหายไป (ตอนแรกนึกว่าเป็นเมฆ):**
  - เกมวาดแสงนี้และจานท้องฟ้าเป็น triangle fan ซึ่ง wgpu ไม่มี
  - fan ที่ไม่มี index วาดผ่าน index list ที่ทำไว้ครั้งเดียว
  - fan ที่มี index ทำ list จากสำเนา CPU ของ index buffer (เก็บสำเนาของทุก buffer ที่ใช้เป็น index)
  - มี test `triangle_fans_draw_every_triangle` ครอบไว้
  - ส่วนเมฆวาดตรงอยู่แล้ว
- **ขอบโลโก้:** บน Vulkan ตรง OpenGL ทุก pixel ความต่างที่เห็นในรอบแรกมาจาก DX12
- **ความเร็ว:**
  - เก็บ bind group ไว้ใช้ซ้ำ และให้ uniform กับ storage buffer ใช้ dynamic offset: section ของ terrain ผูก buffer เดิมที่ offset ใหม่ทุก draw จึงใช้ bind group เดียวกันได้
  - การเขียนใช้ `StagingBelt` ของ wgpu แทนการสร้าง staging buffer ใหม่ทุกครั้ง
  - fence ไม่ submit ทันที: fence หมายถึง submit ถัดไป และจะ submit ก่อนกำหนดเฉพาะเมื่อมีคนรอจริง เกมสร้าง fence หลายตัวต่อเฟรม และ pool ของเกมถามแบบไม่รอทุกเฟรม
- **multi-draw และ indirect draw:**
  - รายงานรองรับเหมือน Vulkan ของ Mojang: multi-draw แบบ interleaved ส่งไป Rust ในครั้งเดียว ส่วน indirect ใช้ `multi_draw_indexed_indirect` ของ wgpu เมื่อมี `INDIRECT_FIRST_INSTANCE`
  - terrain ของเกมจึงเปลี่ยนไปใช้ทาง indirect เอง
  - แบบ separate (ทรงของ OpenGL) แปลงเป็น interleaved ให้
- **timestamp query:** ใช้ timestamp query ของ wgpu ค่าเป็นนาโนวินาที (period 1 เหมือน OpenGL) อ่านแบบไม่รอ GPU และวัด calibration offset ครั้งเดียวตอนสร้าง device มี test `timestamps_read_back_in_order` ครอบไว้
- **หน้าต่างนอก Windows:** อ่าน handle ของ X11, Wayland และ Cocoa (`SDL_Metal_CreateView`) จาก SDL แล้ว ยังทดสอบบนเครื่องนี้ไม่ได้ เพราะมีแต่ Windows
- **เครื่องมือใหม่:**
  - `--uncapped` และ `--world` ในตัวทดลอง
  - `-Dhz.jfr=ไฟล์` เก็บ JFR ก่อนเกมปิด (เกมปิดด้วย `halt` จึงไม่มี exit hook)
  - `HZ_WGPU_WGSL=โฟลเดอร์` เขียน shader ที่แปลงแล้ว
  - `HZ_WGPU_OVERRIDE=โฟลเดอร์` แทน shader ของ pipeline ด้วย WGSL
  - `HZ_WGPU_DUMP_ALL` เก็บ texture และผลของแต่ละ pass
  - `HZ_WGPU_HEADLESS` วาดโดยไม่ขึ้นจอ (ตอนนี้ไม่ต้องให้ API รองรับหน้าต่างด้วย)

### รอบที่สาม (2026-11-26): เก็บของที่ค้าง

- **ความเร็ว: แซง Vulkan ของ Mojang** (วัดสลับกันรอบละตัว เพราะความเร็วของเครื่องเปลี่ยนไปมาเกิน 30% ในชั่วโมงเดียว):

| backend | ms ต่อเฟรม (3 รอบ) |
|---|---:|
| Vulkan (Mojang) | 3.89–3.99 |
| **wgpu (Horizon)** | **3.85–3.88** |

  - ภาพยังถูก: หน้า title ตรง OpenGL ทุก pixel, ในโลกต่างจาก OpenGL 0.0018 (Vulkan ของ Mojang 0.040)
  - **ปิดการตรวจ indirect draw ของ wgpu:** build แบบ release ของ wgpu ยังเปิด `VALIDATION_INDIRECT_CALL` ซึ่งรัน compute pass ตรวจ argument ก่อนทุก indirect draw (กันหน้าเว็บที่ประสงค์ร้ายในเบราว์เซอร์)
    - terrain ของเกมวาดผ่าน indirect จึงโดนทุกเฟรม
    - ปิดแล้วเวลาลดจาก 7.1 เหลือ 5.6 ms ในรอบที่วัดติดกัน (Vulkan ของ Mojang ก็ไม่ได้ตรวจ)
    - `WGPU_VALIDATION_INDIRECT_CALL=1` และ `WGPU_*` ตัวอื่นเปิดกลับได้
  - **ส่งงานให้ queue บน thread แยก:** วัดด้วย `HZ_WGPU_PROFILE=1` แล้วพบว่า render thread ใช้เวลาใน Rust 2.6 ms ต่อเฟรม เป็นการ `finish` ของ encoder (wgpu replay และตรวจทุกคำสั่ง), submit และ present รวม 1.7 ms
    - thread `horizon-wgpu queue` จึงรับงานของ queue ทั้งหมดตามลำดับ (เขียน buffer/texture, finish + submit, ขอ texture ของจอ, blit, present) ระหว่างที่ render thread บันทึกเฟรมถัดไป
    - render thread รอ thread นี้เฉพาะก่อน map buffer (copy ลง buffer นั้นอาจยังไม่ถูก submit), ก่อน configure หน้าต่าง และก่อนคืนหน่วยความจำของ `StagingBelt`
    - เกมขอ texture ของจอตอน**ต้น**เฟรม ถ้าขอจริงตรงนั้นต้องรอ present ของเฟรมก่อนจนไม่เหลือส่วนที่ทำพร้อมกัน texture จึงถูกขอโดย thread ของ queue ตอน present และคำตอบที่ให้เกม (occluded, out of date) มาจากรอบก่อน
    - ตอนนี้ render thread รอ thread นี้เหลือราว 0.04 ms ต่อเฟรม
    - `HZ_WGPU_SYNC=1` ทำทุกอย่างบน render thread แบบเดิม
  - **submit ของเกมรอรวมกับของ present:** เหลือ submit เดียวต่อเฟรม และจะ submit ก่อนก็ต่อเมื่อมีคนรอ fence
  - **อ่าน environment ครั้งเดียว:** สวิตช์ดีบัก `HZ_WGPU_*` เคยถูกอ่านทุก draw ที่ state เปลี่ยนและทุก pass ซึ่งบน Windows ต้องถือ lock และแปลงสตริงทุกครั้ง
  - **ไม่ตั้ง vertex buffer, index buffer หรือ bind group ซ้ำกับที่ผูกอยู่แล้ว**
- **wgpu สร้าง device ไม่ได้ → เกมค้าง:** ไม่ได้ค้างจริง แต่เกมไม่เหลือ backend อื่นให้ลอง จึงเปิดกล่อง error ที่รอคนกด
  - ตอนนี้ `hz.render=wgpu` จะถอยไปใช้ backend ของเกมเอง ทดสอบด้วย `HZ_WGPU_BACKEND=metal` บน Windows (ไม่มี adapter): เกมเปิดด้วย OpenGL
  - ตอนจับภาพ กล่อง error ของเกมกลายเป็นข้อความใน log แล้วเกมปิดเอง (ใช้เวลา 25 วินาที แทนการค้างจนครบ 10 นาที)
  - รายงานของตัวทดลองบอกด้วยว่าภาพวาดด้วย backend ไหน (`drawn by`)
- **ตอนจับภาพ เกมไม่อ่านเมาส์จริง:** ถ้าเมาส์ค้างอยู่บนหน้าต่าง ปุ่มใต้เมาส์จะเรืองแสงในรอบหนึ่งแต่ไม่เรืองในอีกรอบ (เจอจริงในรอบนี้ ปุ่ม Multiplayer ต่างไป 1.5 หมื่น pixel)
- **log ของ wgpu:** crate มี logger แล้ว เหตุผลที่ wgpu-hal ปฏิเสธ adapter หรือ surface จึงขึ้นใน log ของเกม (`HZ_WGPU_LOG=info|debug|trace` ดูละเอียดขึ้น)
- **macOS:** หน้าต่างของ backend นี้และหน้าต่างทดสอบมี `SDL_WINDOW_METAL` ตามที่ SDL ต้องการสำหรับ Metal view

- **DX12: ขอบโลโก้ตรง OpenGL ทุก pixel แล้ว** (เดิมต่าง 1% ของ pixel ในกรอบโลโก้)
  - หาสาเหตุด้วย shader ที่แทนเข้าไป (`HZ_WGPU_OVERRIDE`) ทีละขั้น:
    - พิกัด texture ที่ shader ได้ตรงกับ Vulkan ทุก pixel (เทียบที่ความละเอียด 16 bit)
    - ล็อก mip level เป็น 0 แล้วยังต่างเท่าเดิม จึงไม่เกี่ยวกับการเลือก mip
    - อ่าน texel ตรง ๆ ด้วย `textureLoad` แล้วตรงกัน ข้อมูลของ texture จึงเหมือนกัน
  - สาเหตุ: เกมวาดโลโก้แบบ 1:1 ด้วย sampler แบบ nearest พิกัดทุกจุดจึงตกบนขอบ texel พอดี ไดรเวอร์ D3D12 ของ AMD ปัดไป texel ถัดไป ส่วน Vulkan และ OpenGL บนการ์ดเดียวกันเลือก texel ก่อนหน้า
  - แก้: บน D3D12 texture ที่ผูก sampler แบบ nearest และไม่มี mip level เกินระดับแรก จะถูกอ่านที่กึ่งกลาง texel (`shader::snap_nearest`; pipeline ได้ variant ของ fragment shader ตาม texture ที่ผูกอยู่)
    - กึ่งกลาง texel เป็น texel เดียวกันในทุก API
    - texture ที่มี mipmap ไม่แตะ: ลองแล้ว ถ้าอ่านด้วย gradient ที่ shader คำนวณเอง บาง pixel จะได้ mip level ต่างจากเดิม
    - `HZ_WGPU_SNAP=0` ปิด, `=1` เปิดใน API อื่นด้วย (บน Vulkan เปิดแล้วภาพในโลกไม่เปลี่ยน)
  - ผล DX12: หน้า title ตรงทุก pixel ส่วนในโลก ที่ต่างเกินจาก noise ระหว่างรอบมีแค่ผิวน้ำ ซึ่งเฟรมของแอนิเมชันน้ำตกคนละช่วงในแต่ละรอบ

### ที่ยังเหลือ

- **Vulkan ของ wgpu ไม่รับหน้าต่างของเกม (เจอเมื่อคืน 2026-11-25 ทุกรอบตอนไม่มีคนใช้เครื่อง):** ทำให้เกิดซ้ำไม่ได้แล้ว
  - ลองปิดจอด้วยคำสั่งของ Windows และลองล็อกหน้าจอ: Vulkan ของ wgpu ยังรับหน้าต่างได้ปกติทั้งสองแบบ
  - ถ้าเกิดอีก log จะบอกว่า Vulkan call ตัวไหนตอบปฏิเสธ ระหว่างนั้นเกมใช้ DX12 ซึ่งตอนนี้วาดตรง OpenGL เท่ากับ Vulkan
- **Linux และ macOS:** ผู้ใช้ให้พักไว้ก่อน (2026-11-26) โค้ดของหน้าต่างมีแล้วแต่ยังไม่ได้รันจริง

## ผล P4 (2026-11-26): ระบบไฟของเกมจริงกระจายแสงด้วย Rust

ตัวทดลอง: `python tools/shared_memory_probe.py --light [--reuse] [--rust-only] [--speed N]`

- **ฝั่ง Rust:** `crates/horizon-shm-probe/src/light.rs`
  - port ของ `LightEngine.propagateDecreases`/`propagateIncreases` กับ `propagateDecrease`/`propagateIncrease` ของ `BlockLightEngine` และ `SkyLightEngine` (26.3) ทีละคำสั่ง ใช้คิวเดียวกันในลำดับเดียวกัน ค่าแสงทุกค่าที่เขียนจึงเป็นค่าที่ vanilla เขียน
  - อ่านเขียนแสงใน `RustDataLayer` ของ P2 ตรง ๆ (ไม่คัดลอก)
- **ฝั่ง Java:** `RustLight` และ `LightNames` ใน `mods/horizon/src/shared-memory-probe/`
  - Agent เปลี่ยน `runLightUpdates`: ที่เรียก `propagateDecreases()` ให้เรียก `RustLight.propagate(this)` และที่เรียก `propagateIncreases()` ให้คืน 0 ส่วนที่เหลือของ `runLightUpdates` (`checkNode`, `markNewInconsistencies`, `swapSectionMap`) เป็น Java เดิม
  - รันบน thread แสงของเกมเอง ตามกฎของ P2
- **สิ่งที่ Rust ถาม Java (upcall ผ่าน FFM) ครั้งเดียวต่อ section ต่อรอบ:**
  - layer ของ section: การเขียนครั้งแรกผ่าน copy-on-write ของ `setStoredLevel` ใน Java แผนที่ของเกมจึงถูกเสมอ
  - บล็อกของ section เป็น "class แสง" (ค่าที่ระบบไฟถามบล็อก: emission, dampening, รูปทรงที่บังแสง 6 ด้าน) เก็บเป็น snapshot ต่อ `LevelChunkSection`
    - 1 byte ต่อบล็อก (4 KB ต่อ section) ก่อน Rust รันครั้งแรก Java ตั้ง class ให้ทุก block state ในเกม (vanilla มี 141 class) ถ้าเกิน 256 จะใช้ Java แทน
    - สร้างครั้งแรกจาก palette กับ storage ของ section (palette แปลงครั้งเดียว แล้วไล่ storage ตามลำดับ)
    - หลังจากนั้น `setBlockState` ของเกมเขียนตามทันที (Agent เติม hook) และ `read` ของ client เติมใหม่ทั้ง section
    - มี version กันกรณีเกมเขียนบล็อกระหว่างสร้าง snapshot
  - `lightOnInSection` และ `Shapes.faceShapeOccludes` ของคู่หน้า (จำไว้ทั้งเกม)
- **class แสงมาจาก method ของ `BlockState` ใน Java:** บล็อกของม็อดที่ override ค่าเหล่านี้จึงได้ค่าของม็อดเอง
- **แผนว่าปลอดภัยไหม:** Agent เทียบ bytes ของคลาสระบบไฟ 7 ตัว และ `LevelChunkSection` กับ `PalettedContainer` (ที่ snapshot อ่านตรง ๆ) กับใน jar ตอนเกมโหลด ถ้าตัวไหนถูก loader หรือม็อดแก้ จะกลับไปใช้ Java ทั้งหมด (`java runs` ในรายงาน)

### ผล (โลก normal 144 chunk เดียวกับ P2)

- **แก้บล็อก 40,000 จุด รอแสงนิ่ง แล้วเซฟ:** บล็อก, biome, แสงบล็อก, แสงท้องฟ้า, heightmap และ NBT ที่เซฟ ต่าง 0/144 (vanilla เทียบกับตัวเองก็ 0)
  - ตรงทั้งกับรอบ vanilla ผ่าน hook (เรียก method เดิมของเกม) และกับรอบของ P2 ที่ไม่มี hook เลย
- **ให้แสงทั้งโลกใหม่ (เส้นทางเดียวกับตอนสร้าง chunk):** โหลดเซฟเดิมแบบไม่มีแสง (`-Dhz.relight=true`: ทิ้ง `BlockLight`/`SkyLight` และ `isLightOn` เป็น false) เกมจึงให้แสงทุก chunk ตั้งแต่ศูนย์ด้วย `propagateLightSources` ตามด้วยการกระจาย ผลต่าง 0/144 ทุกอย่างเช่นกัน
- **ฝั่ง client:** เปิด client จริงเข้าโลกด้วย agent นี้ (`HZ_JVM_EXTRA` ของตัวทดลอง P3) ระบบไฟของ client บน Render thread และของ server ในตัวเกมรันด้วย Rust ทั้งคู่ ภาพต่างจาก vanilla 0.0005–0.026 ซึ่งเท่ากับที่ vanilla ต่างจากตัวเองระหว่างรอบ (0.026)
- **Rust รันทุกรอบ:** ไม่มีรอบไหนกลับไปใช้ Java
- **ความเร็ว (`--speed 3`: vanilla กับ Rust สลับกันรอบละตัว เพราะความเร็วของเครื่องเปลี่ยนไปมาเกือบเท่าตัวในวันเดียว; เวลารวมของการกระจายแสงจากการแก้ 40,000 จุด ราว 13.4–13.9 ล้าน entry):**

| รอบ | vanilla | **Rust** |
|---|---:|---:|
| 1 | 9.07 s | **5.70 s** |
| 2 | 8.79 s | **5.55 s** |
| 3 | 8.60 s | **5.60 s** |

  - ในเวลาของ Rust มีการสร้าง snapshot และการถาม layer ฝั่ง Java รวมอยู่ราว 0.5–0.9 s
  - งานของ Rust เองในโลกจริงช้ากว่าใน benchmark ที่ข้อมูลอยู่ใน cache ทั้งหมด (`bench_block_light_flood`, 130 ns ต่อ entry) เพราะต้องรอหน่วยความจำ: โลกนี้ใช้ 3,400 section

### สิ่งที่เจอระหว่างทาง

- **palette เพิ่มระหว่างสร้าง snapshot:** worldgen หรือ server thread ใส่บล็อกชนิดใหม่ขณะ thread แสงอ่าน storage จึงเจอ id ที่เกินขนาด palette ที่อ่านไว้ แก้โดยถาม palette เพิ่มตอนเจอ แล้วให้ version สั่งอ่านซ้ำ
- **อ่าน environment ทุกครั้งแพง:** `std::env::var_os` ต่อการเรียกหนึ่งครั้งช้ากว่างานแสงทั้ง entry (เจอทั้งใน P3 และตอนทดลองนี้)
- **มี `find` ของผมค้างอยู่เบื้องหลังกิน CPU ไปหลายชั่วโมง:** (คำสั่งหาตัว decompile ทั้งดิสก์ที่หยุด task แล้วแต่โปรเซสยังไม่ตาย) ทำให้ตัวเลขช่วงหนึ่งช้าลง เจอแล้วปิดแล้ว และตัวเลขข้างบนวัดแบบสลับกันอยู่แล้ว
- **`HashMap` ปกติของ Rust (SipHash) ช้าเกินไปสำหรับการหา section ทุก neighbor:** ใช้ hash แบบคูณครั้งเดียว และหา section ครั้งเดียวต่อ entry neighbor ใน section เดียวกันไม่ต้องหาซ้ำ

### ที่ยังเหลือ

- **`checkNode` และ `propagateLightSources` ยังเป็น Java:** สองอย่างนี้แค่ใส่งานลงคิว (`SkyLightEngine.propagateLightSources` เติมแสง 15 ทั้งคอลัมน์ตอนสร้าง chunk ด้วย) จึงเป็นส่วนเล็กของเวลา ย้ายมา Rust ได้ด้วยวิธีเดียวกันถ้าวัดแล้วคุ้ม
- **ยังไม่ได้ตรวจ `LevelChunk.getBlockState`:** snapshot อ่าน section ตรง ๆ ข้ามเมธอดนี้ ม็อดที่แก้เมธอดนี้ (หรือโลก debug ของ vanilla ที่มีบล็อกพิเศษ) จะเห็นค่าต่างจากที่ระบบไฟของ Java เห็น
- **ทั้งหมดยังอยู่ในตัวทดลอง P2/P4:** ยังไม่ได้ย้ายเข้าตัวม็อดจริง

## เอาอะไรจาก forge/ari มาใช้

host JNI (`horizon-java-host`), views, bridges, routes (`routes.rs`: Edge/Body), การ export facts และ cache เนื้อหา, harness ที่เทียบกับ loader จริง สิ่งที่เปลี่ยนคือฝั่งที่เป็นหลัก (Java) และจุดที่เข้าไปแทรก (agent แทน `ClassProcessor`)
