// cmd_decoder.v — Frame parser with 0xAA sync + XOR checksum + command extraction
// Frame: 0xAA | CMD | R | G | B | XOR (6 bytes total)
// CMD[7:6] = mode (00=static, 01=breathing, 10=chasing, 11=reserved)
// CMD[5:0] = reserved

module cmd_decoder (
    input  wire       clk,
    input  wire       rst_n,
    input  wire [7:0] rx_byte,
    input  wire       rx_vld,

    // Decoded output
    output reg  [1:0] cmd_mode,     // 00=static, 01=breathing, 10=chasing
    output reg  [5:0] cmd_r,
    output reg  [5:0] cmd_g,
    output reg  [5:0] cmd_b,
    output reg        cmd_vld,      // Pulse: valid command received

    // Frame error flag
    output reg        frame_err     // Pulse: XOR mismatch or frame timeout
);

    localparam S_IDLE       = 3'd0;
    localparam S_WAIT_HEAD  = 3'd1;  // Wait for 0xAA
    localparam S_CMD        = 3'd2;
    localparam S_R          = 3'd3;
    localparam S_G          = 3'd4;
    localparam S_B          = 3'd5;
    localparam S_XOR        = 3'd6;
    localparam S_VALIDATE   = 3'd7;

    // Frame timeout: if bytes stop arriving mid-frame, reset to idle.
    // 20-bit counter @ 50MHz → 1,048,575 counts ≈ 21ms timeout.
    // 21ms at 115200 bps = ~240 byte-times, far longer than any valid 6-byte frame.
    localparam TIMEOUT_MAX = 20'd1_000_000;

    reg [2:0] state;
    reg [7:0] byte_buf [0:4];  // CMD,R,G,B,XOR
    reg [7:0] xor_calc;
    reg [19:0] timeout_cnt;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state     <= S_IDLE;
            byte_buf[0] <= 8'd0;
            byte_buf[1] <= 8'd0;
            byte_buf[2] <= 8'd0;
            byte_buf[3] <= 8'd0;
            byte_buf[4] <= 8'd0;
            xor_calc  <= 8'd0;
            timeout_cnt <= 20'd0;
            cmd_mode  <= 2'd0;
            cmd_r     <= 6'd0;
            cmd_g     <= 6'd0;
            cmd_b     <= 6'd0;
            cmd_vld   <= 1'b0;
            frame_err <= 1'b0;
        end else begin
            cmd_vld   <= 1'b0;
            frame_err <= 1'b0;

            // ── Frame timeout: guard against stuck mid-frame ──
            // Count only when inside a frame (past S_WAIT_HEAD).
            // S_IDLE and S_WAIT_HEAD are waiting states — no timeout needed.
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
                        end
                        // else: stay in WAIT_HEAD, slide to find 0xAA
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

                default: state <= S_IDLE;
            endcase
        end
    end

endmodule
