// rgb_controller_top.v — Top-level: RGB Bluetooth LED Controller
// EP4CE15F17C8 (Cyclone IV), CH9143 BLE module, dual PMOD RGB LED board
//
// PMOD-A: CH9143 BLE → FPGA (TXD→RX, RXD→TX, BLESTA)
// PMOD-B: RGB LED board (IO[0:5]=LED1+LED2, IO[6:7]=LED3 reserved)
//
// Clock: 50MHz on-board oscillator

module rgb_controller_top (
    input  wire       clk_50m,       // 50MHz system clock
    input  wire       rst_n,         // Active-low reset (button)

    // PMOD-A: CH9143 BLE module
    input  wire       pmod_a_rx,     // CH9143 TXD → FPGA RX
    output wire       pmod_a_tx,     // FPGA TX → CH9143 RXD
    input  wire       pmod_a_blesta, // CH9143 BLESTA pin

    // PMOD-B: RGB LED board (6 PWM + 2 reserved)
    output wire       pmod_b_io0,    // LED1_R
    output wire       pmod_b_io1,    // LED1_G
    output wire       pmod_b_io2,    // LED1_B
    output wire       pmod_b_io3,    // LED2_R
    output wire       pmod_b_io4,    // LED2_G
    output wire       pmod_b_io5,    // LED2_B
    output wire       pmod_b_io6,    // LED3_R (reserved)
    output wire       pmod_b_io7     // LED3_G (reserved)
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
    wire       bt_connected;

    // Effect Engine → PWM generators (6 channels, 8-bit duty)
    wire [7:0] duty_led1_r, duty_led1_g, duty_led1_b;
    wire [7:0] duty_led2_r, duty_led2_g, duty_led2_b;

    // PWM tick shared across all channels
    wire       pwm_tick;

    // ─── 0xBB status frame TX ───
    // Frame: 0xBB | STATUS | R_CUR | G_CUR | B_CUR | XOR  (6 bytes)
    // STATUS: [7:6]=cur_mode, [5]=bt_connected, [4:0]=0

    reg  [2:0] tx_frm_idx;        // 0-5 byte index within frame
    reg        tx_frm_active;      // Frame transmission in progress
    reg  [7:0] frm_status;        // Captured STATUS byte
    reg  [7:0] frm_r, frm_g, frm_b; // Captured duty values (top 6 bits)
    reg  [7:0] frm_xor;           // Running XOR accumulator

    wire       tx_start;
    wire [7:0] tx_byte;
    wire       tx_busy;

    // Frame byte lookup: mux correct byte based on tx_frm_idx
    reg  [7:0] frm_byte;
    always @(*) begin
        case (tx_frm_idx)
            3'd0: frm_byte = 8'hBB;            // Frame header
            3'd1: frm_byte = frm_status;       // STATUS
            3'd2: frm_byte = frm_r;            // R_CUR
            3'd3: frm_byte = frm_g;            // G_CUR
            3'd4: frm_byte = frm_b;            // B_CUR
            3'd5: frm_byte = frm_xor;          // XOR checksum
            default: frm_byte = 8'hBB;
        endcase
    end

    assign tx_start = tx_frm_active && !tx_busy;
    assign tx_byte  = frm_byte;

    // ─── Status frame transmission FSM ───
    // On cmd_vld: capture current state, compute XOR, start sending.
    // Each byte: wait for tx_start to go high (tx_busy low), then advance.
    // After byte 5 sent, return to idle.

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
                frm_status    <= {cmd_mode, bt_connected, 5'd0};
                frm_r         <= duty_led1_r;   // Top 6 bits valid, rest zeroed by protocol
                frm_g         <= duty_led1_g;
                frm_b         <= duty_led1_b;
                frm_xor       <= 8'hBB ^ {cmd_mode, bt_connected, 5'd0}
                                       ^ duty_led1_r
                                       ^ duty_led1_g
                                       ^ duty_led1_b;
                tx_frm_active <= 1'b1;
                tx_frm_idx    <= 3'd0;
            end else if (tx_start) begin
                // Byte transmitted — advance to next byte
                if (tx_frm_idx == 3'd5) begin
                    // Frame complete
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
        .clk         (clk_50m),
        .rst_n       (rst_n),
        .rx_byte     (rx_byte),
        .rx_vld      (rx_vld),
        .blesta      (pmod_a_blesta),
        .cmd_mode    (cmd_mode),
        .cmd_r       (cmd_r),
        .cmd_g       (cmd_g),
        .cmd_b       (cmd_b),
        .cmd_vld     (cmd_vld),
        .frame_err   (frame_err),
        .bt_connected(bt_connected)
    );

    // Effect Engine (Static / Breathing / Chasing)
    effect_engine #(
        .PWM_WIDTH(8)
    ) u_effect (
        .clk         (clk_50m),
        .rst_n       (rst_n),
        .bt_connected(bt_connected),
        .cmd_mode    (cmd_mode),
        .cmd_r       (cmd_r),
        .cmd_g       (cmd_g),
        .cmd_b       (cmd_b),
        .cmd_vld     (cmd_vld),
        .pwm_tick    (pwm_tick),
        .duty_led1_r (duty_led1_r),
        .duty_led1_g (duty_led1_g),
        .duty_led1_b (duty_led1_b),
        .duty_led2_r (duty_led2_r),
        .duty_led2_g (duty_led2_g),
        .duty_led2_b (duty_led2_b)
    );

    // PWM Generator × 6 (8-bit, 200Hz, double-buffered)
    // Channel 1 provides shared pwm_tick
    pwm_generator #(.WIDTH(8), .CLK_FREQ(50_000_000), .PWM_FREQ(200))
    u_pwm_led1_r (.clk(clk_50m), .rst_n(rst_n), .duty_in(duty_led1_r), .duty_vld(1'b1), .pwm_out(pmod_b_io0), .pwm_tick(pwm_tick));
    pwm_generator #(.WIDTH(8), .CLK_FREQ(50_000_000), .PWM_FREQ(200))
    u_pwm_led1_g (.clk(clk_50m), .rst_n(rst_n), .duty_in(duty_led1_g), .duty_vld(1'b1), .pwm_out(pmod_b_io1), .pwm_tick());
    pwm_generator #(.WIDTH(8), .CLK_FREQ(50_000_000), .PWM_FREQ(200))
    u_pwm_led1_b (.clk(clk_50m), .rst_n(rst_n), .duty_in(duty_led1_b), .duty_vld(1'b1), .pwm_out(pmod_b_io2), .pwm_tick());
    pwm_generator #(.WIDTH(8), .CLK_FREQ(50_000_000), .PWM_FREQ(200))
    u_pwm_led2_r (.clk(clk_50m), .rst_n(rst_n), .duty_in(duty_led2_r), .duty_vld(1'b1), .pwm_out(pmod_b_io3), .pwm_tick());
    pwm_generator #(.WIDTH(8), .CLK_FREQ(50_000_000), .PWM_FREQ(200))
    u_pwm_led2_g (.clk(clk_50m), .rst_n(rst_n), .duty_in(duty_led2_g), .duty_vld(1'b1), .pwm_out(pmod_b_io4), .pwm_tick());
    pwm_generator #(.WIDTH(8), .CLK_FREQ(50_000_000), .PWM_FREQ(200))
    u_pwm_led2_b (.clk(clk_50m), .rst_n(rst_n), .duty_in(duty_led2_b), .duty_vld(1'b1), .pwm_out(pmod_b_io5), .pwm_tick());

    // Reserved LED3 — tied low
    assign pmod_b_io6 = 1'b0;
    assign pmod_b_io7 = 1'b0;

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
