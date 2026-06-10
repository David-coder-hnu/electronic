// rgb_controller_top.v — Top-level: RGB Bluetooth LED Controller (WS2812B)
// EP4CE15F17C8 (Cyclone IV), CH9143 BLE module, 8× WS2812B RGB LEDs
//
// PMOD-A: CH9143 BLE → FPGA (TXD→RX, RXD→TX)
// LED:    Single WS2812B data line → 8 cascaded RGB LEDs (192-bit frame)
//
// Clock: 50MHz on-board oscillator

module rgb_controller_top (
    input  wire       clk_50m,       // 50MHz system clock
    input  wire       rst_n,         // Active-low reset (button)

    // PMOD-A: CH9143 BLE module (UART only)
    input  wire       pmod_a_rx,     // CH9143 TXD → FPGA RX
    output wire       pmod_a_tx,     // FPGA TX → CH9143 RXD

    // WS2812B LED output
    output wire       led_out        // 8-LED cascade data line (PIN_T2)
);

    // ─── Internal signals ───

    // UART RX → CMD Decoder
    wire [7:0] rx_byte;
    wire       rx_vld;
    wire       rx_err;

    // CMD Decoder → Effect Engine
    wire [1:0] cmd_mode;
    wire [5:0] cmd_r, cmd_g, cmd_b;
    wire       cmd_vld;
    wire       frame_err;

    // Effect Engine → WS2812B Driver
    wire [191:0] led_data;
    wire         update;
    wire         ws2812_busy;

    // ─── 0xBB status frame TX ───
    // Frame: 0xBB | STATUS | R_CUR | G_CUR | B_CUR | XOR  (6 bytes)
    // STATUS: [7:6]=cur_mode, [5]=0, [4:0]=0

    reg  [2:0] tx_frm_idx;          // 0-5 byte index within frame
    reg        tx_frm_active;        // Frame transmission in progress
    reg  [7:0] frm_status;          // Captured STATUS byte
    reg  [7:0] frm_r, frm_g, frm_b; // Captured duty values (top 6 bits)
    reg  [7:0] frm_xor;             // Running XOR accumulator

    wire       tx_start;
    wire [7:0] tx_byte;
    wire       tx_busy;

    // Extract top 6 bits of LED0 color for status frame
    // led_data[191:168] = LED0 {G[7:0], R[7:0], B[7:0]}
    wire [5:0] led0_r_6b = led_data[181:176];
    wire [5:0] led0_g_6b = led_data[189:184];
    wire [5:0] led0_b_6b = led_data[173:168];

    // Frame byte lookup: mux correct byte based on tx_frm_idx
    reg  [7:0] frm_byte;
    always @(*) begin
        case (tx_frm_idx)
            3'd0: frm_byte = 8'hBB;                // Frame header
            3'd1: frm_byte = frm_status;           // STATUS
            3'd2: frm_byte = frm_r;                // R_CUR
            3'd3: frm_byte = frm_g;                // G_CUR
            3'd4: frm_byte = frm_b;                // B_CUR
            3'd5: frm_byte = frm_xor;              // XOR checksum
            default: frm_byte = 8'hBB;
        endcase
    end

    assign tx_start = tx_frm_active && !tx_busy;
    assign tx_byte  = frm_byte;

    // ─── Status frame transmission FSM ───
    // On cmd_vld: capture current LED0 color, compute XOR, start sending.

    always @(posedge clk_50m or negedge rst_n) begin
        if (!rst_n) begin
            tx_frm_active <= 1'b0;
            tx_frm_idx    <= 3'd0;
            frm_status    <= 8'd0;
            frm_r         <= 8'd0;
            frm_g         <= 8'd0;
            frm_b         <= 8'd0;
            frm_xor       <= 8'd0;
        end else begin
            if (cmd_vld) begin
                // Capture current state and start frame
                frm_status    <= {cmd_mode, 6'd0};
                frm_r         <= {led0_r_6b, 2'b00};
                frm_g         <= {led0_g_6b, 2'b00};
                frm_b         <= {led0_b_6b, 2'b00};
                frm_xor       <= 8'hBB
                               ^ {cmd_mode, 6'd0}
                               ^ {led0_r_6b, 2'b00}
                               ^ {led0_g_6b, 2'b00}
                               ^ {led0_b_6b, 2'b00};
                tx_frm_active <= 1'b1;
                tx_frm_idx    <= 3'd0;
            end else if (tx_start) begin
                // Byte transmitted — advance to next byte
                if (tx_frm_idx == 3'd5) begin
                    tx_frm_active <= 1'b0;
                    tx_frm_idx    <= 3'd0;
                end else begin
                    tx_frm_idx <= tx_frm_idx + 1'b1;
                end
            end
        end
    end

    // ─── Module instantiations ───

    // UART Receiver (115200 bps, 16x oversampling, CH9143 input)
    uart_rx #(
        .CLK_FREQ(50_000_000),
        .BAUD_RATE(115200)
    ) u_uart_rx (
        .clk     (clk_50m),
        .rst_n   (rst_n),
        .rx      (pmod_a_rx),
        .rx_byte (rx_byte),
        .rx_vld  (rx_vld),
        .rx_err  (rx_err)
    );

    // Command Decoder (0xAA frame sync, XOR checksum)
    cmd_decoder u_cmd_dec (
        .clk      (clk_50m),
        .rst_n    (rst_n),
        .rx_byte  (rx_byte),
        .rx_vld   (rx_vld),
        .cmd_mode (cmd_mode),
        .cmd_r    (cmd_r),
        .cmd_g    (cmd_g),
        .cmd_b    (cmd_b),
        .cmd_vld  (cmd_vld),
        .frame_err(frame_err)
    );

    // Effect Engine (Static / Breathing / Chasing → 192-bit GRB)
    effect_engine u_effect (
        .clk      (clk_50m),
        .rst_n    (rst_n),
        .cmd_mode (cmd_mode),
        .cmd_r    (cmd_r),
        .cmd_g    (cmd_g),
        .cmd_b    (cmd_b),
        .cmd_vld  (cmd_vld),
        .led_data (led_data),
        .update   (update)
    );

    // WS2812B Serial Driver (192 bits → single data line)
    ws2812_driver u_ws2812 (
        .clk      (clk_50m),
        .rst_n    (rst_n),
        .led_data (led_data),
        .update   (update),
        .led_out  (led_out),
        .busy     (ws2812_busy)
    );

    // UART Transmitter — driven by status frame FSM
    uart_tx #(
        .CLK_FREQ(50_000_000),
        .BAUD_RATE(115200)
    ) u_uart_tx (
        .clk     (clk_50m),
        .rst_n   (rst_n),
        .tx_byte (tx_byte),
        .tx_start(tx_start),
        .tx      (pmod_a_tx),
        .tx_busy (tx_busy)
    );

endmodule
