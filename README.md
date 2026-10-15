# MultiThread_Zero-Copy

โปรเจกต์สำหรับทดลองดาวน์โหลดไฟล์ผ่าน TCP โดยใช้ Java หลาย Workers และเปรียบเทียบการอ่าน/ส่งไฟล์แบบ IO กับ NIO

## Requirements

- Java 21 ขึ้นไป (ใช้ Virtual Threads)
- เปิด Server ก่อนเรียกใช้งาน Client

## Project Structure

```text
MultiThread_Zero-Copy/
├── files/
│   └── test.bin
├── src/
│   ├── Server.java
│   └── Client.java
└── README.md
```

## Compile

รันคำสั่งจากโฟลเดอร์หลักของโปรเจกต์:

```powershell
javac -d src src\Server.java src\Client.java
```

## Run Server

เปิด Terminal แรก แล้วรันจากโฟลเดอร์หลักของโปรเจกต์:

```powershell
java -cp src Server
```

Server ใช้พอร์ต `5000` และให้บริการไฟล์ภายในโฟลเดอร์ `files/`

## Client Commands

เปิด Terminal อีกหน้าต่างหนึ่ง แล้วใช้คำสั่งต่อไปนี้จากโฟลเดอร์หลักของโปรเจกต์

### 1. List files

```powershell
java -cp src Client LIST
```

ขอรายชื่อไฟล์ที่ Server อนุญาตให้ดาวน์โหลด

### 2. Get file information

```powershell
java -cp src Client INFO test.bin
```

ขอขนาดไฟล์ก่อนดาวน์โหลด หากไม่พบไฟล์หรือ Request ไม่ถูกต้อง Server จะตอบ `ERROR`

### 3. Get file hash

```powershell
java -cp src Client HASH test.bin
```

ขอค่า SHA-256 ของไฟล์จาก Server สำหรับใช้ตรวจสอบความถูกต้อง

### 4. Download a file

```powershell
java -cp src Client test.bin output.bin 10 NIO
```

รูปแบบคำสั่ง:

```text
java -cp src Client <file> <output> <workers> <IO|NIO>
```

- `<file>`: ชื่อไฟล์ต้นทางที่อยู่ใน `files/`
- `<output>`: path ของไฟล์ปลายทาง
- `<workers>`: จำนวน Workers เช่น `10`
- `<IO|NIO>`: วิธีรับส่งข้อมูลที่ต้องการทดสอบ

ตัวอย่างใช้ IO:

```powershell
java -cp src Client test.bin output.bin 10 IO
```

## Protocol: Request / Response

Protocol ใช้ข้อความสำหรับคำสั่งและ Header ส่วนข้อมูลไฟล์ (`GET`) เป็น Binary Payload

| Request | หน้าที่ | Response ตัวอย่าง |
|---|---|---|
| `LIST` | ขอรายชื่อไฟล์ที่ดาวน์โหลดได้ | `FILE test.bin 1048576` ต่อด้วย `END` เมื่อจบรายการ |
| `INFO test.bin` | ขอข้อมูลไฟล์ก่อนดาวน์โหลด | `SIZE 1048576` หรือ `ERROR ...` |
| `GET test.bin 0 1024 NIO` | ขอข้อมูลเฉพาะช่วงของไฟล์ | `OK 1024` ตามด้วย Binary Payload จำนวน 1,024 bytes หรือ `ERROR ...` |
| `HASH test.bin` | ขอค่า SHA-256 ของไฟล์ | `HASH <sha256>` หรือ `ERROR ...` |
| Request ไม่ถูกต้อง | แจ้งคำสั่งหรือช่วงข้อมูลไม่ถูกต้อง | `ERROR <code> <message>` |

### GET: Offset, Length, and Payload

รูปแบบ Request:

```text
GET <file> <offset> <length> [IO|NIO]
```

- `offset`: ตำแหน่งเริ่มต้นของข้อมูลในไฟล์ โดยเริ่มนับจาก byte `0`
- `length`: จำนวน byte ที่ต้องการอ่าน
- เมื่อสำเร็จ Server ส่ง Header `OK <length>` แล้วส่ง Binary Payload ให้ครบตามจำนวน byte ที่ตกลงกัน
- หากไฟล์ไม่ถูกต้องหรือช่วงข้อมูลอยู่นอกขอบเขต Server ส่ง `ERROR` ที่ Client สามารถนำไปจัดการได้

Client ต้องอ่าน Header ให้ครบก่อน แล้วอ่าน Payload ตามจำนวน byte ที่ระบุ ห้ามใช้ newline หรือการปิด connection เป็นตัวแบ่งขอบเขตของ Binary Payload

## วิธีตรวจสอบด้วย PowerShell หากไฟล์ที่บันทึกไว้เป็น payload โดยตรง:

```text
(Get-Item .\files\xxxxx).Length
```

## วิธีหาว่าไฟล์มีกี่ bytes

```text
(Get-Item .\files\xxxxxx).Length
```

## 10-Worker Download Flow

1. Client ขอรายชื่อไฟล์ด้วย `LIST` และขนาดไฟล์ด้วย `INFO`
2. Client แบ่งไฟล์เป็น byte ranges สำหรับ 10 Workers โดยแต่ละช่วงไม่ทับซ้อนและไม่ตกหล่น
3. แต่ละ Worker เปิด TCP connection ของตัวเอง ส่ง `GET` พร้อม `offset` และ `length` แล้วรับเฉพาะข้อมูลในช่วงนั้น
4. Server handler อ่านเฉพาะช่วงที่ Worker ร้องขอ และส่งข้อมูลตามจำนวน byte ที่กำหนด
5. เมื่อ Workers ทำงานเสร็จ Client รวม part files หรือเขียนข้อมูลลงไฟล์ปลายทางตามตำแหน่ง `offset`
6. Client ตรวจสอบขนาดไฟล์ปลายทาง และเปรียบเทียบ SHA-256 กับค่าจาก Server

ช่วงที่ Worker รับคือ byte ตั้งแต่ `start` จำนวน `length` bytes หรือ `[start, end)` จึงไม่ซ้ำกันและครอบคลุมไฟล์ทั้งหมด แม้ขนาดไฟล์จะหาร 10 ไม่ลงตัว Worker ที่ได้ `length = 0` สามารถข้ามการดาวน์โหลดได้

## Validation

หลังดาวน์โหลดเสร็จ ให้ตรวจสอบว่า:

- ขนาดไฟล์ปลายทางเท่ากับขนาดไฟล์ต้นทางที่ Server แจ้งจาก `INFO`
- ค่า SHA-256 ของไฟล์ปลายทางตรงกับค่าที่ Server แจ้งจาก `HASH`
- หากขนาดหรือ hash ไม่ตรง ให้ถือว่าการดาวน์โหลดไม่ผ่านการตรวจสอบ
