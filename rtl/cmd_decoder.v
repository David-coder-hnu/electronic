// cmd_decoder.v — Frame parser: 0xAA light commands + 0xAC config frames
// 0xAA frame (6 bytes): 0xAA | CMD | R | G | B | XOR
//   CMD[7:6] = mode (00=static, 01=breathing, 10=chasing, 11=reserved)
// 0xAC frame (5 bytes): 0xAC | PARAM_ID | VALUE_H | VALUE_L | XOR
//   PARAM_ID: 0=breath period, 1=chase speed, 2=max brightness

module cmd_decoder (
    input  wire       clk,
    input  wire       rst_n,
    input  wire [7:0] rx_byte,
    input  wire       rx_vld,

    // 0xAA light command output
    output reg  [1:0] cmd_mode,     // 00=static, 01=breathing, 10=chasing
    output reg  [5:0] cmd_r,
    output reg  [5:0] cmd_g,
    output reg  [5:0] cmd_b,
    output reg        cmd_vld,      // Pulse: valid light command received

    // 0xAC config output
    output reg  [1:0] cfg_id,       // 0=breath period, 1=chase speed, 2=max brightness
    output reg [15:0] cfg_value,    // Config value (10-bit range per param)
    output reg        cfg_vld,      // Pulse: valid config received

    // Frame error flag
    output reg        frame_err     // Pulse: XOR mismatch or frame timeout
);

    localparam S_IDLE       = 3'd0;
    localparam S_WAIT_HEAD  = 3'd1;  // Wait for 0xAA or 0xAC
    localparam S_CMD        = 3'd2;
    localparam S_R          = 3'd3;
    localparam S_G          = 3'd4;
    localparam S_B          = 3'd5;
    localparam S_XOR        = 3'd6;
    localparam S_VALIDATE   = 3'd7;

    // 0xAC config frame states
    localparam S_AC_PARAM   = 3'd2;  // Reuse same indices after branch
    // Actually use separate states for clarity
    // — we reuse the 3-bit state space with an ac_sel flag instead

    // Simpler: expand state to 4 bits to accommodate both paths
    localparam S_AC_WAIT    = 4'd8;
    localparam S_AC_PARAM_S = 4'd9;
    localparam S_AC_VH      = 4'd10;
    localparam S_AC_VL      = 4'd11;
    localparam S_AC_XOR_S   = 4'd12;
    localparam S_AC_VALID   = 4'd13;

    // Frame timeout: if bytes stop arriving mid-frame, reset to idle.
    // 20-bit counter @ 50MHz → 1,048,575 counts ≈ 21ms timeout.
    // 21ms at 115200 bps = ~240 byte-times, far longer than any valid 6-byte frame.
    localparam TIMEOUT_MAX = 20'd1_000_000;

    reg [3:0] state;
    reg [7:0] byte_buf [0:4];  // CMD,R,G,B,XOR  (0xAA frame)
    reg [7:0] xor_calc;
    reg [19:0] timeout_cnt;

    // 0xAC config frame registers
    reg [7:0] ac_param;        // PARAM_ID
    reg [7:0] ac_val_h;        // VALUE_H
    reg [7:0] ac_val_l;        // VALUE_L
    reg [7:0] ac_xor;          // Config XOR accumulator

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state     <= S_IDLE;
            byte_buf[0] <= 8'd0;
            byte_buf[1] <= 8'd0;
            byte_buf[2] <= 8'd0;
            byte_buf[3] <= 8'd0;
            byte_buf[4] <= 8'd0;
            xor_calc  <= 8'd0;
            ac_param  <= 8'd0;
            ac_val_h  <= 8'd0;
            ac_val_l  <= 8'd0;
            ac_xor    <= 8'd0;
            timeout_cnt <= 20'd0;
            cmd_mode  <= 2'd0;
            cmd_r     <= 6'd0;
            cmd_g     <= 6'd0;
            cmd_b     <= 6'd0;
            cmd_vld   <= 1'b0;
            cfg_id    <= 2'd0;
            cfg_value <= 16'd0;
            cfg_vld   <= 1'b0;
            frame_err <= 1'b0;
        end else begin
            cmd_vld   <= 1'b0;
            cfg_vld   <= 1'b0;
            frame_err <= 1'b0;

            // ── Frame timeout: guard against stuck mid-frame ──
            // Count only when inside a frame (past S_WAIT_HEAD).
            if (state != S_IDLE && state != S_WAIT_HEAD) begin
                if (timeout_cnt >= TIMEOUT_MAX) begin
                    // Timeout — abort partial frame, return to idle
                    state       <= S_IDLE;
                    timeout_cnt <= 20'd0;
                    frame_err   <= 1'b1;
                end else begin
                    timeout_cnt <= timeout_cnt + 1'b1;
                end
            end else begin
                timeout_cnt <= 20'd0;
            end

            case (state)
                S_IDLE: begin
                    state <= S_WAIT_HEAD;
                end

                S_WAIT_HEAD: begin
                    if (rx_vld) begin
                        if (rx_byte == 8'hAA) begin
                            xor_calc <= 8'hAA;     // Start XOR accumulator
                            state    <= S_CMD;
                        end else if (rx_byte == 8'hAC) begin
                            ac_xor <= 8'hAC;       // Start AC XOR accumulator
                            state  <= S_AC_PARAM_S;
                        end
                        // else: stay in WAIT_HEAD, slide to find frame header
                    end
                end

                S_CMD: begin
                    if (rx_vld) begin
                        byte_buf[0] <= rx_byte;
                        xor_calc    <= xor_calc ^ rx_byte;
                        state       <= S_R;
                    end
                end

                S_R: begin
                    if (rx_vld) begin
                        byte_buf[1] <= rx_byte;
                        xor_calc    <= xor_calc ^ rx_byte;
                        state       <= S_G;
                    end
                end

                S_G: begin
                    if (rx_vld) begin
                        byte_buf[2] <= rx_byte;
                        xor_calc    <= xor_calc ^ rx_byte;
                        state       <= S_B;
                    end
                end

                S_B: begin
                    if (rx_vld) begin
                        byte_buf[3] <= rx_byte;
                        xor_calc    <= xor_calc ^ rx_byte;
                        state       <= S_XOR;
                    end
                end

                S_XOR: begin
                    if (rx_vld) begin
                        byte_buf[4] <= rx_byte;
                        state       <= S_VALIDATE;
                    end
                end

                S_VALIDATE: begin
                    if (xor_calc == byte_buf[4]) begin
                        cmd_mode <= byte_buf[0][7:6];
                        cmd_r    <= byte_buf[1][5:0];
                        cmd_g    <= byte_buf[2][5:0];
                        cmd_b    <= byte_buf[3][5:0];
                        cmd_vld  <= 1'b1;
                    end else begin
                        frame_err <= 1'b1;
                    end
                    state <= S_IDLE;
                end

                // ── 0xAC Config Frame: 0xAC | PARAM_ID | VALUE_H | VALUE_L | XOR ──
                // PARAM_ID: 0=breath period, 1=chase speed, 2=max brightness

                S_AC_PARAM_S: begin
                    if (rx_vld) begin
                        ac_param <= rx_byte;
                        ac_xor   <= ac_xor ^ rx_byte;
                        state    <= S_AC_VH;
                    end
                end

                S_AC_VH: begin
                    if (rx_vld) begin
                        ac_val_h <= rx_byte;
                        ac_xor   <= ac_xor ^ rx_byte;
                        state    <= S_AC_VL;
                    end
                end

                S_AC_VL: begin
                    if (rx_vld) begin
                        ac_val_l <= rx_byte;
                        ac_xor   <= ac_xor ^ rx_byte;
                        state    <= S_AC_XOR_S;
                    end
                end

                S_AC_XOR_S: begin
                    if (rx_vld) begin
                        if (ac_xor == rx_byte) begin
                            // XOR checksum match — decode config
                            cfg_id    <= ac_param[1:0];
                            cfg_value <= {ac_val_h[7:0], ac_val_l[7:0]};
                            cfg_vld   <= 1'b1;
                        end else begin
                            frame_err <= 1'b1;
                        end
                        state <= S_IDLE;
                    end
                end

                default: state <= S_IDLE;
            endcase
        end
    end

endmodule
