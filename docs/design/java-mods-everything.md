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

1. **P1 Agent + diff ของ bytecode:** บูต Fabric 26.3 และ NeoForge 26.3 จริงพร้อมม็อดตัวอย่างใน JVM ธรรมดา แล้วให้ agent ออกรายงานว่าม็อดแตะ method ไหน ยังไม่ต้องใช้ Horizon เครื่องนี้มี Fabric 26.3 อยู่แล้ว
2. **P2 หน่วยความจำร่วม:** `PalettedContainer` ของ Java บนหน่วยความจำของ Rust เซฟแล้วได้ผลตรงกับ vanilla ทุก byte
3. **P3 วาดภาพ:** หน้า title ของ Java client จริงวาดผ่าน backend wgpu
4. **P4 accelerator ตัวแรก:** ระบบไฟ เปิดเมื่อแผนบอกว่าปลอดภัย และผ่าน parity harness เดิม

## ผล P1 (2026-11-25)

รัน `python tools/loader_probe.py` กับโปรไฟล์ "Fabric 26.3" (Fabric Loader 0.19.5) และม็อด 22 ตัวในเครื่องของผู้พัฒนา ได้แก่ Fabric API, Sodium, Iris, C2ME, ViaFabricPlus, Litematica ฯลฯ ใช้เวลาโหลด 11 วินาที และเทียบ 3 วินาที

- **คลาส vanilla 11,383 ตัว โหลดผ่าน loader ได้ครบ** ไม่ล้มเลยสักตัว
- **ไม่ถูกแตะเลย 10,389 คลาส (91%)** ส่วน 994 คลาสถูกแก้ method ไป 1,604 ตัว (อีก 757 ตัวเปลี่ยนแค่สิทธิ์การเข้าถึง เพราะ access widener)
- **ตัวที่แก้เยอะสุด:** Fabric API 551 ตัว ซึ่งเป็น patch ของ loader ที่รู้จัก (`RUST_WITH_MOMENTS`), ViaFabricPlus 434, C2ME 177 และ Iris 145
- **ระบบไฟถูกแตะแค่ 3 method และ redstone ไม่ถูกแตะเลย:** จึงเป็นตัวเริ่มที่ดีสำหรับ accelerator ส่วน worldgen ถูก C2ME แตะ 74 ตัว ซึ่งเป็นม็อดเร่งความเร็วที่ Horizon มาแทนอยู่แล้ว
- **ระบุที่มาได้ครบทุก method แล้ว** (รอบที่สอง): ตัวทดลองอ่าน mixin config ในม็อดเองโดยตรง เจอ mixin 2,238 คลาสที่เล็งคลาส vanilla 932 ตัว ส่วน `<init>`/`<clinit>` ที่ mixin ใส่ค่าเริ่มต้นให้ field ก็ระบุได้จาก field ที่ mixin ประกาศ และการเปลี่ยนสิทธิ์การเข้าถึง 757 ตัวระบุได้จากไฟล์ access widener ของแต่ละม็อด (Fabric API 677, Sodium 291 ฯลฯ)
- **ข้อ D ได้หลักฐานแล้ว:** ใน jar ของ 26.3 ระบบวาดภาพแยกเป็น API กลาง (`com/mojang/renderpearl/api`: `GpuBackend`, `GpuDevice`, `CommandEncoder`) และมี backend สองตัวคือ `backend/opengl` กับ `backend/vulkan` backend ตัวที่สามที่ทำด้วย wgpu จึงมีที่เสียบรออยู่แล้ว

### NeoForge 26.3.0.118 (ม็อด 13 ตัว: Sodium, Lithium, FerriteCore, ImmediatelyFast, Entity Culling, AE2, GuideME, JEI, Mezz Config, AppleSkin, Xaero's Minimap, Architectury, Cloth Config)

- **ข้อค้นพบสำคัญ:** NeoForge สร้าง jar ของเกมใหม่ด้วยการ decompile แล้ว compile กลับ (`minecraft-client-patched`) bytecode เลยต่างจาก vanilla ถึง 86,812 method ทั้งที่ NeoForge แก้จริงแค่บางส่วน เช่น `ARGB.computeSrgbToLinear` ที่ NeoForge ไม่ได้แตะเลยก็ยังต่าง
  - **ผลต่อการออกแบบ:** baseline สำหรับม็อดต้องเป็น **loader ตัวเดียวกันที่ไม่ลงม็อด** ไม่ใช่ vanilla (ตัวทดลองรันสองรอบอัตโนมัติแล้ว)
  - **การแก้ของ NeoForge เองหาได้จาก "สิ่งที่ method อ้างถึง":** เทียบการเรียก method, field, type และค่าคงที่แบบเรียงลำดับ แทนการเทียบคำสั่งตรง ๆ การ compile ใหม่จะย้ายตำแหน่ง branch แต่ไม่เปลี่ยนสิ่งที่อ้างถึง ส่วน patch จะเปลี่ยน ผลคือ method ที่ต่าง 86,812 ตัว ลดเหลือที่แก้จริง 2,186 ตัว (อีก 71,279 เป็นแค่ compile ใหม่) และไม่นับ lambda เพราะชื่อถูกเรียงเลขใหม่
  - **ตรวจกับ source patch จริงของ NeoForge (850 ไฟล์ จาก branch `26.3.x`):** ตัวทดลองเจอ 96% ของคลาสที่มี patch ส่วนคลาสที่ตัวทดลองเจอแต่ไม่มีไฟล์ patch มี 244 คลาสที่เปลี่ยนแค่สิทธิ์การเข้าถึง (มาจาก access transformer ของ NeoForge เอง ไม่ได้อยู่ใน patch) และประมาณ 600 method ที่อาจเป็นสิ่งรบกวนจากการ compile ใหม่ หรือ coremod ของ NeoForge ตัวเลขนี้จึงเป็นขอบบน ฝั่งที่ปลอดภัย (อาจสั่งให้ Java รันเกินจำเป็น แต่ไม่ทำให้ม็อดพัง)
- **ม็อดแก้เพิ่มจาก NeoForge 610 method** (นับใหม่หลังแยกการเปลี่ยนสิทธิ์ 48 ตัวออก) ระบุที่มาไม่ได้แค่ 1 ตัว: Lithium มากที่สุด ตามด้วย Sodium, ImmediatelyFast, Xaero, Architectury และ AE2
- **ระบบไฟและ network ไม่ถูกม็อดแตะเลย** redstone ถูกแตะแค่ 4 (Lithium) ส่วนม็อดเนื้อหาใหญ่อย่าง AE2 แตะ method vanilla แค่ 15 ตัว
- **ตัดออกจากชุด:** Iris NeoForge 26.3 (1.11.7) ใช้ร่วมกับ Sodium รุ่นไหนก็ไม่ได้ จึงเอาออก และใช้ Sodium 0.9.3 แทน 0.9.4-beta.2

### Forge 26.3-66.1.9 (ม็อด 11 ตัว: Biomes O' Plenty, GlitchCore, TerraBlender, GeckoLib, Xaero's Minimap, Xaero's World Map, Collective, Mouse Tweaks, Sound Physics Remastered, Iceberg, Forge Config API Port)

- **บูตผ่าน ไม่ล้มเลยสักคลาส** Forge ใช้ `net.minecraftforge.bootstrap.ForgeBootstrap` และแก้เกมด้วย binary patch ทีละคลาส คลาสที่แก้ถูก compile ใหม่ด้วย (มี 16,526 method ที่เป็นแค่ compile ใหม่)
- **Forge แก้เกมเอง 1,287 method** ส่วนใหญ่อยู่ใน entity (305) และ block (145) ระบบไฟถูกแตะ 1 ตัว และ redstone ไม่ถูกแตะ
- **ม็อดแก้เพิ่มจาก Forge 133 method** ระบุที่มาไม่ได้ 7 ตัว ส่วนการเปลี่ยนสิทธิ์ 425 ตัวระบุได้ครบ (access transformer แบบ wildcard `*()` นับเป็นทั้งคลาส) ระบบไฟ, redstone และ network ไม่ถูกม็อดแตะเลย
- **ม็อด worldgen ขนาดใหญ่อย่าง Biomes O' Plenty แทบไม่แตะ method ของเกม** ตัวที่แตะ worldgen คือ TerraBlender (10)

### Quilt Loader 0.20.0-beta.9

- **บูต 26.3 ไม่ได้ เป็นข้อจำกัดของ Quilt เอง ไม่ใช่ของตัวทดลอง:** Quilt ใช้ ASM 9.5 ซึ่งอ่านคลาสของ Java 25 (major version 69) ไม่ได้ จึงล้มตั้งแต่ `GameTransformer.locateEntrypoints` ก่อนโหลดม็อดใด ๆ และ QSL ก็ยังไม่มีรุ่นสำหรับ 26.3
- **ผลต่อการออกแบบ:** ตอนนี้ผู้เล่น 26.3 ใช้ Quilt ไม่ได้ ไม่ว่าจะเป็นเกมจริงหรือ Horizon พอ Quilt ออกรุ่นที่รองรับแล้ว ให้รันตัวทดลองซ้ำด้วยคำสั่งเดิม

**สรุปสี่ตัว:** การเทียบกับ loader ที่ไม่ลงม็อดได้ผลทั้ง Fabric, NeoForge และ Forge จึงใช้เป็นวิธีหลักได้ ส่วน Quilt ยังรองรับ 26.3 ไม่ได้ ระบบไฟและ redstone ว่างให้ Rust เร่งได้ในทั้งสามแพ็ก ส่วนที่ถูกแตะมักเป็นม็อดเร่งความเร็ว (Lithium, C2ME, Sodium) ซึ่งเป็นงานที่ Horizon จะทำแทนอยู่แล้ว

## เอาอะไรจาก forge/ari มาใช้

host JNI (`horizon-java-host`), views, bridges, routes (`routes.rs`: Edge/Body), การ export facts และ cache เนื้อหา, harness ที่เทียบกับ loader จริง สิ่งที่เปลี่ยนคือฝั่งที่เป็นหลัก (Java) และจุดที่เข้าไปแทรก (agent แทน `ClassProcessor`)
