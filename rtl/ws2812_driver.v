// ws2812_driver.v — WS2812B serial LED driver
// 50MHz clock → bit period 63 cycles (1.26µs), reset 3000 cycles (60µs)
// 192 bits = 8 LEDs × 24 bits (G-R-B per LED, MSB first)
// LED0 (closest to FPGA) gets the first 24 bits in the stream.

module ws2812_driver (
    input  wire         clk,
    input  wire         rst_n,
    input  wire [191:0] led_data,   // 8 LEDs × 24bit GRB, LED0 in MSB
    input  wire         update,     // Pulse: latch led_data and start TX
    output reg          led_out,    // WS2812B data line
    output reg          busy        // High during TX + reset
);

    // ─── WS2812B timing at 50MHz (20ns/cycle) ───
    // Nominal values centered for best tolerance margin:
    // T0H=0.40µs → 20cyc(0.40µs) ✓ spec 0.25–0.55µs
    // T0L=0.85µs → 43cyc(0.86µs) ✓ spec 0.70–1.00µs
    // T1H=0.80µs → 40cyc(0.80µs) ✓ spec 0.65–0.95µs
    // T1L=0.45µs → 23cyc(0.46µs) ✓ spec 0.30–0.60µs
    // RESET >50µs → 3000cyc(60µs) ✓ above minimum
    localparam BIT_TOTAL    = 7'd63;     // 63 cycles per bit (1.26µs)
    localparam T0H          = 7'd20;     // 0-code high pulse (0.40µs, nominal)
    localparam T1H          = 7'd40;     // 1-code high pulse (0.80µs, nominal)
    localparam RESET_CYCLES = 12'd3000;  // reset low (60µs)

    localparam S_IDLE  = 2'd0;
    localparam S_SEND  = 2'd1;
    localparam S_RESET = 2'd2;

    reg [1:0]   state;
    reg [191:0] shift_reg;   // Shift register, bit[191] sent first
    reg [7:0]   bit_idx;     // 0–191
    reg [6:0]   bit_cnt;     // 0–62 within one bit
    reg [11:0]  reset_cnt;   // reset low counter

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state     <= S_IDLE;
            led_out   <= 1'b1;
            busy      <= 1'b0;
            shift_reg <= 192'd0;
            bit_idx   <= 8'd0;
            bit_cnt   <= 7'd0;
            reset_cnt <= 12'd0;
        end else begin
            case (state)
                S_IDLE: begin
                    led_out <= 1'b1;
                    busy    <= 1'b0;
                    if (update) begin
                        shift_reg <= led_data;
                        bit_idx   <= 8'd0;
                        bit_cnt   <= 7'd0;
                        busy      <= 1'b1;
                        state     <= S_SEND;
                    end
                end

                S_SEND: begin
                    // Drive high for T0H (0-bit) or T1H (1-bit), then low
                    if (shift_reg[191])
                        led_out <= (bit_cnt < T1H);
                    else
                        led_out <= (bit_cnt < T0H);

                    if (bit_cnt == BIT_TOTAL - 1) begin
                        bit_cnt   <= 7'd0;
                        shift_reg <= {shift_reg[190:0], 1'b0};
                        if (bit_idx == 8'd191) begin
                            state     <= S_RESET;
                            reset_cnt <= 12'd0;
                        end else begin
                            bit_idx <= bit_idx + 8'd1;
                        end
                    end else begin
                        bit_cnt <= bit_cnt + 7'd1;
                    end
                end

                S_RESET: begin
                    led_out <= 1'b0;
                    if (reset_cnt == RESET_CYCLES - 1) begin
                        state <= S_IDLE;
                    end else begin
                        reset_cnt <= reset_cnt + 12'd1;
                    end
                end

                default: state <= S_IDLE;
            endcase
        end
    end

endmodule
