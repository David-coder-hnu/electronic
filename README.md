# RGB 彩灯蓝牙控制器

湖南大学 计算机学院实验中心 C301 — 电子测量工具与平台 综合实验选题三

**手机 APP → 蓝牙 BLE → FPGA (Verilog) → PWM → RGB LED**

全栈硬件闭环：从 Android 应用到 Cyclone IV 纯硬件逻辑，无软核 CPU。

## 架构

```
┌──────────┐   BLE GATT    ┌─────────────────────────────────┐
│ 手机 APP  │ ────────────→ │  CH9143 BLE 模块 (PMOD-A)       │
│ · 色轮取色 │              │  Baud 115200, 8N1               │
│ · 模式切换 │              │                                  │
│ · 场景保存 │              │  ┌───────────────────────────┐  │
└──────────┘              │  │  Verilog @ Cyclone IV      │  │
                          │  │  uart_rx → cmd_decoder     │  │
                          │  │     → effect_engine        │  │
                          │  │     → pwm_generator ×6     │  │
                          │  └───────────────────────────┘  │
                          │                 │                │
                          │  ┌──────────────▼────────────┐   │
                          │  │  自制 PMOD RGB LED 板      │   │
                          │  │  8 IO: 2灯独立 + 第3灯预留 │   │
                          │  └───────────────────────────┘   │
                          └─────────────────────────────────┘
```

## 目录结构

| 目录 | 内容 |
|------|------|
| `docs/` | 主设计文档、UI 设计规范、选题说明 |
| `rtl/` | Verilog 源码 (6 modules) |
| `tb/` | ModelSim testbench (3 个) |
| `pcb/` | PMOD RGB LED 扩展板设计 |
| `app/` | Android APP (Kotlin + Jetpack Compose) |

## 灯效

- **静态色** — 色轮取色，实时发送
- **呼吸** — PWL 分段线性逼近 sin 曲线，~3 秒完整周期
- **流水** — 双灯交替追逐，相位差 π

## 指令协议

```
下行帧 (APP → FPGA): 0xAA | CMD | R | G | B | XOR  (6 bytes)
上行帧 (FPGA → APP): 0xBB | STATUS | R_CUR | G_CUR | B_CUR | XOR

CMD[7:6] = 模式 (00=静态, 01=呼吸, 10=流水, 11=保留)
R/G/B[5:0] = 6-bit 色深 (0-63)
```

## 构建

### FPGA (Quartus)

1. 打开 Quartus II，新建工程指向 `rtl/`
2. 设置 `rgb_controller_top` 为顶层模块
3. 分配引脚 → 编译 → 烧录

### Android APP

```bash
cd app
# 用 Android Studio 打开，或命令行:
./gradlew assembleDebug
```

## 硬件物料

- FPGA: Cyclone IV EP4CE15F17C8
- 蓝牙: CH9143 BLE 模块 (PMOD-A)
- LED: 共阴 RGB LED ×2-3 (PMOD-B, 自制)
- PCB: 40mm × 25mm 双面板, 嘉立创打样

## 许可

课程项目 — 湖南大学 计算机学院 C301
