# Multithreaded File Download

โปรเจกต์ดาวน์โหลดไฟล์ผ่าน TCP Client/Server โดยใช้ Multithreading และเปรียบเทียบ Traditional I/O กับ NIO

## Features
- รองรับ Client หลายตัวพร้อมกัน
- แบ่งไฟล์เป็น 10 ช่วงเพื่อดาวน์โหลดพร้อมกัน
- รองรับ Traditional I/O และ NIO (`transferTo`)
- ใช้ Java Virtual Threads
- ตรวจสอบความถูกต้องด้วย SHA-256

## Requirements
- Java 21 หรือใหม่กว่า
- ไฟล์ที่ต้องการดาวน์โหลดอยู่ในโฟลเดอร์ `files/`

## Compile

```bash
javac src/*.java
```

## Run Server

```bash
java -cp src Server
```

## Run Client

แสดงรายการไฟล์:
```bash
java -cp src Client LIST
```

ดูขนาดไฟล์:
```bash
java -cp src Client INFO test.bin
```

ตรวจสอบ SHA-256:
```bash
java -cp src Client HASH test.bin
```

ดาวน์โหลดไฟล์ด้วย 10 Workers และ NIO:
```bash
java -cp src Client test.bin output.bin 10 NIO
```

ดาวน์โหลดด้วย Traditional I/O:
```bash
java -cp src Client test.bin output.bin 10 IO
```

## Testing
เปรียบเทียบ IO และ NIO โดยทดสอบ 1 และ 10 Workers อย่างละ 3 รอบ บันทึกเวลาและ Throughput จากการรันจริง พร้อมตรวจสอบขนาดไฟล์และ SHA-256 เพื่อยืนยันว่าไฟล์ที่ดาวน์โหลดถูกต้อง

## Protocol

| คำสั่งตัวอย่าง | หน้าที่ | ตัวอย่างผลตอบกลับ |
|---|---|---|
| `LIST` | ขอรายชื่อไฟล์ที่ดาวน์โหลดได้ | `FILE <filename> <size>` และ `END` |
| `INFO <filename>` | ขอข้อมูลขนาดไฟล์ก่อนเริ่มดาวน์โหลด | `SIZE <bytes>` หรือ `ERROR` |
| `GET <filename> <offset> <length> <IO\|NIO>` | ขอข้อมูลเฉพาะช่วงของไฟล์ โดยส่ง Binary Payload ตามจำนวนไบต์ที่กำหนด | `OK <length>` ตามด้วย Binary Payload หรือ `ERROR` |
| `ERROR` | แจ้งข้อผิดพลาดของคำขอหรือช่วงข้อมูล เพื่อให้ Client นำไปจัดการได้ | `ERROR <code> <message>` |

คำสั่ง `GET` ส่ง Response Header ตามด้วย Binary Payload ตามจำนวนไบต์ที่ร้องขอ