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
    localparam REFRESH_MAX = 18'd250000;

    reg [1:0]  cur_mode;
    reg [5:0]  base_r, base_g, base_b;
    reg [15:0] phase;
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

    // ─── Scale: (base[5:0] * factor[7:0]) / 64 → 8-bit duty ───
    function [7:0] scale;
        input [5:0] base;
        input [7:0] factor;
        reg [13:0] product;
        begin
            product = {2'b0, base} * factor;
            scale   = product[13:6];
        end
    endfunction

    // ─── Chasing sin values: each LED offset by 2 LUT entries (π/8 apart) ───
    wire [7:0] chase_sin0 = sin_lut[(phase_idx + 4'd0)  & 4'hF];
    wire [7:0] chase_sin1 = sin_lut[(phase_idx + 4'd2)  & 4'hF];
    wire [7:0] chase_sin2 = sin_lut[(phase_idx + 4'd4)  & 4'hF];
    wire [7:0] chase_sin3 = sin_lut[(phase_idx + 4'd6)  & 4'hF];
    wire [7:0] chase_sin4 = sin_lut[(phase_idx + 4'd8)  & 4'hF];
    wire [7:0] chase_sin5 = sin_lut[(phase_idx + 4'd10) & 4'hF];
    wire [7:0] chase_sin6 = sin_lut[(phase_idx + 4'd12) & 4'hF];
    wire [7:0] chase_sin7 = sin_lut[(phase_idx + 4'd14) & 4'hF];

    // ─── Per-LED duty registers ───
    reg [7:0] g0, r0, b0;
    reg [7:0] g1, r1, b1;
    reg [7:0] g2, r2, b2;
    reg [7:0] g3, r3, b3;
    reg [7:0] g4, r4, b4;
    reg [7:0] g5, r5, b5;
    reg [7:0] g6, r6, b6;
    reg [7:0] g7, r7, b7;

    // ─── Main logic ───
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            cur_mode <= MODE_STATIC;
            base_r   <= 6'h3F;
            base_g   <= 6'h3F;
            base_b   <= 6'h3F;
            phase    <= 16'd0;
            update   <= 1'b0;
            {g0,r0,b0} <= 24'd0;
            {g1,r1,b1} <= 24'd0;
            {g2,r2,b2} <= 24'd0;
            {g3,r3,b3} <= 24'd0;
            {g4,r4,b4} <= 24'd0;
            {g5,r5,b5} <= 24'd0;
            {g6,r6,b6} <= 24'd0;
            {g7,r7,b7} <= 24'd0;
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
                        r0 <= scale(base_r, 8'hFF); g0 <= scale(base_g, 8'hFF); b0 <= scale(base_b, 8'hFF);
                        r1 <= scale(base_r, 8'hFF); g1 <= scale(base_g, 8'hFF); b1 <= scale(base_b, 8'hFF);
                        r2 <= scale(base_r, 8'hFF); g2 <= scale(base_g, 8'hFF); b2 <= scale(base_b, 8'hFF);
                        r3 <= scale(base_r, 8'hFF); g3 <= scale(base_g, 8'hFF); b3 <= scale(base_b, 8'hFF);
                        r4 <= scale(base_r, 8'hFF); g4 <= scale(base_g, 8'hFF); b4 <= scale(base_b, 8'hFF);
                        r5 <= scale(base_r, 8'hFF); g5 <= scale(base_g, 8'hFF); b5 <= scale(base_b, 8'hFF);
                        r6 <= scale(base_r, 8'hFF); g6 <= scale(base_g, 8'hFF); b6 <= scale(base_b, 8'hFF);
                        r7 <= scale(base_r, 8'hFF); g7 <= scale(base_g, 8'hFF); b7 <= scale(base_b, 8'hFF);
                    end

                    MODE_BREATHING: begin
                        r0 <= scale(base_r, sin_lut[phase_idx]); g0 <= scale(base_g, sin_lut[phase_idx]); b0 <= scale(base_b, sin_lut[phase_idx]);
                        r1 <= scale(base_r, sin_lut[phase_idx]); g1 <= scale(base_g, sin_lut[phase_idx]); b1 <= scale(base_b, sin_lut[phase_idx]);
                        r2 <= scale(base_r, sin_lut[phase_idx]); g2 <= scale(base_g, sin_lut[phase_idx]); b2 <= scale(base_b, sin_lut[phase_idx]);
                        r3 <= scale(base_r, sin_lut[phase_idx]); g3 <= scale(base_g, sin_lut[phase_idx]); b3 <= scale(base_b, sin_lut[phase_idx]);
                        r4 <= scale(base_r, sin_lut[phase_idx]); g4 <= scale(base_g, sin_lut[phase_idx]); b4 <= scale(base_b, sin_lut[phase_idx]);
                        r5 <= scale(base_r, sin_lut[phase_idx]); g5 <= scale(base_g, sin_lut[phase_idx]); b5 <= scale(base_b, sin_lut[phase_idx]);
                        r6 <= scale(base_r, sin_lut[phase_idx]); g6 <= scale(base_g, sin_lut[phase_idx]); b6 <= scale(base_b, sin_lut[phase_idx]);
                        r7 <= scale(base_r, sin_lut[phase_idx]); g7 <= scale(base_g, sin_lut[phase_idx]); b7 <= scale(base_b, sin_lut[phase_idx]);
                    end

                    MODE_CHASING: begin
                        r0 <= scale(base_r, chase_sin0); g0 <= scale(base_g, chase_sin0); b0 <= scale(base_b, chase_sin0);
                        r1 <= scale(base_r, chase_sin1); g1 <= scale(base_g, chase_sin1); b1 <= scale(base_b, chase_sin1);
                        r2 <= scale(base_r, chase_sin2); g2 <= scale(base_g, chase_sin2); b2 <= scale(base_b, chase_sin2);
                        r3 <= scale(base_r, chase_sin3); g3 <= scale(base_g, chase_sin3); b3 <= scale(base_b, chase_sin3);
                        r4 <= scale(base_r, chase_sin4); g4 <= scale(base_g, chase_sin4); b4 <= scale(base_b, chase_sin4);
                        r5 <= scale(base_r, chase_sin5); g5 <= scale(base_g, chase_sin5); b5 <= scale(base_b, chase_sin5);
                        r6 <= scale(base_r, chase_sin6); g6 <= scale(base_g, chase_sin6); b6 <= scale(base_b, chase_sin6);
                        r7 <= scale(base_r, chase_sin7); g7 <= scale(base_g, chase_sin7); b7 <= scale(base_b, chase_sin7);
                    end

                    default: begin
                        r0 <= scale(base_r, 8'hFF); g0 <= scale(base_g, 8'hFF); b0 <= scale(base_b, 8'hFF);
                        r1 <= scale(base_r, 8'hFF); g1 <= scale(base_g, 8'hFF); b1 <= scale(base_b, 8'hFF);
                        r2 <= scale(base_r, 8'hFF); g2 <= scale(base_g, 8'hFF); b2 <= scale(base_b, 8'hFF);
                        r3 <= scale(base_r, 8'hFF); g3 <= scale(base_g, 8'hFF); b3 <= scale(base_b, 8'hFF);
                        r4 <= scale(base_r, 8'hFF); g4 <= scale(base_g, 8'hFF); b4 <= scale(base_b, 8'hFF);
                        r5 <= scale(base_r, 8'hFF); g5 <= scale(base_g, 8'hFF); b5 <= scale(base_b, 8'hFF);
                        r6 <= scale(base_r, 8'hFF); g6 <= scale(base_g, 8'hFF); b6 <= scale(base_b, 8'hFF);
                        r7 <= scale(base_r, 8'hFF); g7 <= scale(base_g, 8'hFF); b7 <= scale(base_b, 8'hFF);
                    end
                endcase

                update <= 1'b1;
            end
        end
    end

    // ─── Assemble 192-bit led_data (combinational, LED0 at MSB) ───
    // GRB per LED: {G[7:0], R[7:0], B[7:0]}
    always @(*) begin
        led_data = {g0, r0, b0, g1, r1, b1, g2, r2, b2, g3, r3, b3,
                    g4, r4, b4, g5, r5, b5, g6, r6, b6, g7, r7, b7};
    end

endmodule
