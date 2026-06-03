// effect_engine.v — RGB LED Effect Engine
// Modes: 00=STATIC, 01=BREATHING (PWL sin approx), 10=CHASING (dual LED)
// Drives 6 PWM channels: LED1(R/G/B) + LED2(R/G/B)

module effect_engine #(
    parameter PWM_WIDTH = 8
) (
    input  wire          clk,
    input  wire          rst_n,
    input  wire          bt_connected,    // CH9143 BLESTA — low = disconnected

    // Command input (from cmd_decoder)
    input  wire [1:0]    cmd_mode,
    input  wire [5:0]    cmd_r,
    input  wire [5:0]    cmd_g,
    input  wire [5:0]    cmd_b,
    input  wire          cmd_vld,

    // PWM tick for breathing timebase (200 Hz)
    input  wire          pwm_tick,

    // PWM duty outputs (6 channels)
    output reg [PWM_WIDTH-1:0] duty_led1_r,
    output reg [PWM_WIDTH-1:0] duty_led1_g,
    output reg [PWM_WIDTH-1:0] duty_led1_b,
    output reg [PWM_WIDTH-1:0] duty_led2_r,
    output reg [PWM_WIDTH-1:0] duty_led2_g,
    output reg [PWM_WIDTH-1:0] duty_led2_b
);

    localparam MODE_STATIC    = 2'b00;
    localparam MODE_BREATHING = 2'b01;
    localparam MODE_CHASING   = 2'b10;

    // Current mode and base color (latched on cmd_vld)
    reg [1:0] cur_mode;
    reg [5:0] base_r, base_g, base_b;

    // Breathing phase: 16-bit accumulator, increment 109 per pwm_tick
    // 200 * 109 = 21800 → wrap 65536 → 65536/21800 ≈ 3.0s period
    reg [15:0] breath_phase;

    // Chasing phase
    reg [15:0] chase_phase;

    // PWL sin ROM: 16 entries, 8-bit, approximates sin(0 to π)
    // Values: sin(0), sin(π/16), sin(2π/16), ... sin(15π/16)
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

    wire [3:0] phase_idx = breath_phase[15:12];  // Top 4 bits → 16-entry LUT
    wire [7:0] sin_val   = sin_lut[phase_idx];

    // Chasing mode: LED2 phase offset by π (half the LUT)
    wire [3:0] chase_idx_led2 = chase_phase[15:12] + 4'd8;
    wire [7:0] sin_led2       = sin_lut[chase_idx_led2];

    // ── Helper functions ──

    // Scale: (base * factor) / 64 → PWM_WIDTH-bit duty
    function [PWM_WIDTH-1:0] scale;
        input [5:0] base;     // 6-bit color (0-63)
        input [7:0] factor;   // 8-bit factor (0-255)
        reg [13:0] product;   // 6+8 = 14 bits
        begin
            product = {2'b0, base} * factor;
            scale   = product[13:6];  // Divide by 64
        end
    endfunction

    // Approximate x / 255 via (x * 257) >> 16.  Error < 0.002%.
    // Replaces 9 hardware divider instances with a single DSP-friendly multiply.
    // x is sin_val(8b) * disc_timer(8b) = 16b product.
    function [7:0] div255;
        input [15:0] x;
        reg [31:0] tmp;
        begin
            tmp    = x * 32'd257;
            div255 = tmp[23:16];  // >> 16, keep 8 bits
        end
    endfunction

    // Disconnect timeout: if BT drops, slowly fade to off over ~2 seconds
    reg [7:0] disc_timer;
    reg       was_connected;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            cur_mode     <= MODE_STATIC;
            base_r       <= 6'h3F;
            base_g       <= 6'h3F;
            base_b       <= 6'h3F;
            breath_phase <= 16'd0;
            chase_phase  <= 16'd0;
            disc_timer   <= 8'd0;
            was_connected <= 1'b0;

            duty_led1_r <= {PWM_WIDTH{1'b0}};
            duty_led1_g <= {PWM_WIDTH{1'b0}};
            duty_led1_b <= {PWM_WIDTH{1'b0}};
            duty_led2_r <= {PWM_WIDTH{1'b0}};
            duty_led2_g <= {PWM_WIDTH{1'b0}};
            duty_led2_b <= {PWM_WIDTH{1'b0}};
        end else begin
            // Latch command
            if (cmd_vld) begin
                cur_mode <= cmd_mode;
                base_r   <= cmd_r;
                base_g   <= cmd_g;
                base_b   <= cmd_b;
            end

            // Disconnect handling: fade to off
            if (!bt_connected) begin
                if (was_connected && disc_timer == 8'd0) begin
                    disc_timer <= 8'd255;  // Start fade
                end
                was_connected <= 1'b0;
            end else begin
                was_connected <= 1'b1;
                disc_timer   <= 8'd0;
            end

            // Phase update
            if (pwm_tick) begin
                breath_phase <= breath_phase + 16'd109;
                chase_phase  <= chase_phase + 16'd109;
            end

            // Fade countdown (one step per pwm_tick, 255 ticks ≈ 1.3s)
            if (pwm_tick && disc_timer > 0)
                disc_timer <= disc_timer - 1'b1;

            // === Effect computation ===
            case (cur_mode)
                MODE_STATIC: begin
                    if (disc_timer > 0) begin
                        duty_led1_r <= scale(base_r, disc_timer);
                        duty_led1_g <= scale(base_g, disc_timer);
                        duty_led1_b <= scale(base_b, disc_timer);
                        duty_led2_r <= scale(base_r, disc_timer);
                        duty_led2_g <= scale(base_g, disc_timer);
                        duty_led2_b <= scale(base_b, disc_timer);
                    end else begin
                        duty_led1_r <= scale(base_r, 8'hFF);
                        duty_led1_g <= scale(base_g, 8'hFF);
                        duty_led1_b <= scale(base_b, 8'hFF);
                        duty_led2_r <= scale(base_r, 8'hFF);
                        duty_led2_g <= scale(base_g, 8'hFF);
                        duty_led2_b <= scale(base_b, 8'hFF);
                    end
                end

                MODE_BREATHING: begin
                    if (disc_timer > 0) begin
                        // div255(sin_val * disc_timer) — multiply-shift, no hardware divider
                        duty_led1_r <= scale(base_r, div255(sin_val * disc_timer));
                        duty_led1_g <= scale(base_g, div255(sin_val * disc_timer));
                        duty_led1_b <= scale(base_b, div255(sin_val * disc_timer));
                    end else begin
                        duty_led1_r <= scale(base_r, sin_val);
                        duty_led1_g <= scale(base_g, sin_val);
                        duty_led1_b <= scale(base_b, sin_val);
                    end
                    // LED2 follows LED1
                    duty_led2_r <= duty_led1_r;
                    duty_led2_g <= duty_led1_g;
                    duty_led2_b <= duty_led1_b;
                end

                MODE_CHASING: begin
                    // LED1 and LED2 alternate: sin_val for LED1, sin(phase+π) for LED2

                    if (disc_timer > 0) begin
                        duty_led1_r <= scale(base_r, div255(sin_val   * disc_timer));
                        duty_led1_g <= scale(base_g, div255(sin_val   * disc_timer));
                        duty_led1_b <= scale(base_b, div255(sin_val   * disc_timer));
                        duty_led2_r <= scale(base_r, div255(sin_led2  * disc_timer));
                        duty_led2_g <= scale(base_g, div255(sin_led2  * disc_timer));
                        duty_led2_b <= scale(base_b, div255(sin_led2  * disc_timer));
                    end else begin
                        duty_led1_r <= scale(base_r, sin_val);
                        duty_led1_g <= scale(base_g, sin_val);
                        duty_led1_b <= scale(base_b, sin_val);
                        duty_led2_r <= scale(base_r, sin_led2);
                        duty_led2_g <= scale(base_g, sin_led2);
                        duty_led2_b <= scale(base_b, sin_led2);
                    end
                end

                default: begin
                    duty_led1_r <= scale(base_r, 8'hFF);
                    duty_led1_g <= scale(base_g, 8'hFF);
                    duty_led1_b <= scale(base_b, 8'hFF);
                    duty_led2_r <= scale(base_r, 8'hFF);
                    duty_led2_g <= scale(base_g, 8'hFF);
                    duty_led2_b <= scale(base_b, 8'hFF);
                end
            endcase
        end
    end

endmodule
