// effect_engine.v — RGB LED Effect Engine (8× WS2812B LEDs)
// Modes: 00=STATIC, 01=BREATHING (PWL sin approx), 10=CHASING (8 LED wave)
// Output: 192-bit GRB packed data for ws2812_driver
//
// Data packing (WS2812B GRB, MSB first per byte, LED0 sent first):
//   led_data[191:168] = LED0: {G[7:0], R[7:0], B[7:0]}
//   led_data[167:144] = LED1
//   ...
//   led_data[ 23:  0] = LED7

module effect_engine (
    input  wire         clk,
    input  wire         rst_n,

    // Command input (from cmd_decoder)
    input  wire [1:0]   cmd_mode,
    input  wire [5:0]   cmd_r,
    input  wire [5:0]   cmd_g,
    input  wire [5:0]   cmd_b,
    input  wire         cmd_vld,

    // WS2812B output — 192 bits for 8 LEDs
    output reg  [191:0] led_data,
    output reg          update        // Pulse: new frame ready for ws2812_driver
);

    localparam MODE_STATIC    = 2'b00;
    localparam MODE_BREATHING = 2'b01;
    localparam MODE_CHASING   = 2'b10;

    // Refresh rate: 50MHz / 250000 ≈ 200Hz
    // Frame TX + reset ≈ 302µs, so driver is idle ~94% of the time.
    localparam REFRESH_MAX = 18'd250000;

    reg [1:0]  cur_mode;
    reg [5:0]  base_r, base_g, base_b;
    reg [15:0] phase;               // Shared phase accumulator (breathing + chasing)
    reg [17:0] refresh_cnt;
    reg        refresh_tick;

    // ─── 16-entry sin LUT (8-bit, piecewise linear approximation) ───
    wire [7:0] sin_lut [0:15];
    assign sin_lut[0]  = 8'd0;
    assign sin_lut[1]  = 8'd50;
    assign sin_lut[2]  = 8'd98;
    assign sin_lut[3]  = 8'd142;
    assign sin_lut[4]  = 8'd181;
    assign sin_lut[5]  = 8'd212;
    assign sin_lut[6]  = 8'd236;
    assign sin_lut[7]  = 8'd251;
    assign sin_lut[8]  = 8'd255;
    assign sin_lut[9]  = 8'd251;
    assign sin_lut[10] = 8'd236;
    assign sin_lut[11] = 8'd212;
    assign sin_lut[12] = 8'd181;
    assign sin_lut[13] = 8'd142;
    assign sin_lut[14] = 8'd98;
    assign sin_lut[15] = 8'd50;

    wire [3:0] phase_idx = phase[15:12];   // Top 4 bits → 16-entry LUT

    // ─── Refresh tick generator ───
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            refresh_cnt <= 18'd0;
            refresh_tick <= 1'b0;
        end else begin
            refresh_tick <= 1'b0;
            if (refresh_cnt == REFRESH_MAX - 1) begin
                refresh_cnt <= 18'd0;
                refresh_tick <= 1'b1;
            end else begin
                refresh_cnt <= refresh_cnt + 18'd1;
            end
        end
    end

    // ─── Scale: (base * factor) / 64 → 8-bit duty ───
    function [7:0] scale;
        input [5:0] base;       // 6-bit color (0–63)
        input [7:0] factor;     // 8-bit factor (0–255)
        reg [13:0] product;
        begin
            product = {2'b0, base} * factor;
            scale   = product[13:6];     // Divide by 64
        end
    endfunction

    // ─── Main logic ───
    integer i;
    reg [3:0] led_phase;     // Phase index for each LED

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            cur_mode <= MODE_STATIC;
            base_r   <= 6'h3F;
            base_g   <= 6'h3F;
            base_b   <= 6'h3F;
            phase    <= 16'd0;
            led_data <= 192'd0;
            update   <= 1'b0;
        end else begin
            update <= 1'b0;

            // Latch new command
            if (cmd_vld) begin
                cur_mode <= cmd_mode;
                base_r   <= cmd_r;
                base_g   <= cmd_g;
                base_b   <= cmd_b;
            end

            // Refresh: update phase → recompute all 8 LEDs → fire update
            if (refresh_tick) begin
                phase <= phase + 16'd109;   // ~3s period for breathing

                case (cur_mode)
                    MODE_STATIC: begin
                        for (i = 0; i < 8; i = i + 1) begin
                            // All LEDs same color, full brightness
                            // LED0 at MSB [191:168], LED7 at LSB [23:0]
                            led_data[(7-i)*24 +: 24] <= {
                                scale(base_g, 8'hFF),
                                scale(base_r, 8'hFF),
                                scale(base_b, 8'hFF)
                            };
                        end
                    end

                    MODE_BREATHING: begin
                        for (i = 0; i < 8; i = i + 1) begin
                            // All LEDs breathe together
                            led_data[(7-i)*24 +: 24] <= {
                                scale(base_g, sin_lut[phase_idx]),
                                scale(base_r, sin_lut[phase_idx]),
                                scale(base_b, sin_lut[phase_idx])
                            };
                        end
                    end

                    MODE_CHASING: begin
                        for (i = 0; i < 8; i = i + 1) begin
                            // Each LED offset by 2 LUT entries (π/8 per LED)
                            led_phase = (phase_idx + (i * 2)) & 4'hF;
                            led_data[(7-i)*24 +: 24] <= {
                                scale(base_g, sin_lut[led_phase]),
                                scale(base_r, sin_lut[led_phase]),
                                scale(base_b, sin_lut[led_phase])
                            };
                        end
                    end

                    default: begin
                        for (i = 0; i < 8; i = i + 1) begin
                            led_data[(7-i)*24 +: 24] <= {
                                scale(base_g, 8'hFF),
                                scale(base_r, 8'hFF),
                                scale(base_b, 8'hFF)
                            };
                        end
                    end
                endcase

                update <= 1'b1;
            end
        end
    end

endmodule
