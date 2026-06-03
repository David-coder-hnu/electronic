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

    // PWM status feedback (for UART TX)
    wire       pwm_tick;

    // UART TX signals
    wire       tx_start;
    wire [7:0] tx_byte;
    wire       tx_busy;

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
    // All share the same pwm_tick from channel 1
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

    // Reserved LED3
    assign pmod_b_io6 = 1'b0;
    assign pmod_b_io7 = 1'b0;

    // UART Transmitter — status feedback
    // Send status frame on each received command
    reg send_status;
    always @(posedge clk_50m or negedge rst_n) begin
        if (!rst_n) send_status <= 1'b0;
        else if (cmd_vld) send_status <= 1'b1;
        else if (tx_start) send_status <= 1'b0;
    end

    assign tx_start = send_status && !tx_busy;
    // Status byte: [7:6]=mode, [5]=bt_connected, [4:0]=reserved
    assign tx_byte = {cmd_mode, bt_connected, 5'd0};

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
