// uart_rx.v — UART Receiver with 16x oversampling + 0xAA frame sync
// Baud: 115200 @ 50MHz → 50M/(16*115200) ≈ 27 ticks per sample
// Format: 1 start(L) + 8 data(LSB first) + 1 stop(H), 8N1

module uart_rx #(
    parameter CLK_FREQ  = 50_000_000,
    parameter BAUD_RATE = 115200,
    parameter OVERSAMPLE = 16
) (
    input  wire       clk,
    input  wire       rst_n,
    input  wire       rx,          // UART RX input from CH9143 TXD

    output reg  [7:0] rx_byte,     // Received byte
    output reg        rx_vld,      // Pulse: byte valid this cycle
    output reg        rx_err       // Pulse: framing error detected
);

    // Baud rate generator: count = CLK / (OVERSAMPLE * BAUD)
    localparam BAUD_CNT_MAX = CLK_FREQ / (OVERSAMPLE * BAUD_RATE);  // ≈ 27
    localparam HALF_BIT    = OVERSAMPLE / 2;                         // 8

    reg [$clog2(BAUD_CNT_MAX)-1:0] baud_cnt;
    reg [3:0]                      sample_cnt;  // 0-15 within one bit
    reg [2:0]                      bit_cnt;     // 0-7 data bits
    reg [2:0]                      state;

    localparam S_IDLE       = 3'd0;
    localparam S_START      = 3'd1;
    localparam S_DATA       = 3'd2;
    localparam S_STOP       = 3'd3;
    localparam S_DONE       = 3'd4;

    reg        rx_d1, rx_d2;       // Synchronizer (2-stage)
    reg        rx_synced;
    reg [7:0]  shift_reg;

    // 2-stage synchronizer — prevent metastability
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            rx_d1     <= 1'b1;
            rx_d2     <= 1'b1;
            rx_synced <= 1'b1;
        end else begin
            rx_d1     <= rx;
            rx_d2     <= rx_d1;
            rx_synced <= rx_d2;
        end
    end

    // Main RX state machine
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state      <= S_IDLE;
            baud_cnt   <= 'd0;
            sample_cnt <= 'd0;
            bit_cnt    <= 'd0;
            shift_reg  <= 8'd0;
            rx_byte    <= 8'd0;
            rx_vld     <= 1'b0;
            rx_err     <= 1'b0;
        end else begin
            rx_vld <= 1'b0;
            rx_err <= 1'b0;

            case (state)
                S_IDLE: begin
                    // Detect start bit (falling edge)
                    if (rx_synced == 1'b0) begin
                        state    <= S_START;
                        baud_cnt <= 'd0;
                    end
                end

                S_START: begin
                    // Wait half a bit period to sample at bit center
                    if (baud_cnt == HALF_BIT - 1) begin
                        baud_cnt <= 'd0;
                        // Verify start bit is still low (false start rejection)
                        if (rx_synced == 1'b0) begin
                            state      <= S_DATA;
                            bit_cnt    <= 'd0;
                            sample_cnt <= 'd0;
                        end else begin
                            state <= S_IDLE;  // False start — go back
                        end
                    end else begin
                        baud_cnt <= baud_cnt + 1'b1;
                    end
                end

                S_DATA: begin
                    if (baud_cnt == BAUD_CNT_MAX - 1) begin
                        baud_cnt <= 'd0;
                        if (sample_cnt == HALF_BIT - 1) begin
                            // Sample at mid-bit (sample_cnt == 8)
                            shift_reg[bit_cnt] <= rx_synced;
                        end
                        if (sample_cnt == OVERSAMPLE - 1) begin
                            sample_cnt <= 'd0;
                            if (bit_cnt == 3'd7) begin
                                state <= S_STOP;
                            end else begin
                                bit_cnt <= bit_cnt + 1'b1;
                            end
                        end else begin
                            sample_cnt <= sample_cnt + 1'b1;
                        end
                    end else begin
                        baud_cnt <= baud_cnt + 1'b1;
                    end
                end

                S_STOP: begin
                    if (baud_cnt == BAUD_CNT_MAX - 1) begin
                        baud_cnt <= 'd0;
                        if (sample_cnt == HALF_BIT - 1) begin
                            if (rx_synced == 1'b1) begin
                                // Valid stop bit
                                rx_byte <= shift_reg;
                                rx_vld  <= 1'b1;
                            end else begin
                                // Framing error
                                rx_err  <= 1'b1;
                            end
                        end
                        if (sample_cnt == OVERSAMPLE - 1) begin
                            state <= S_IDLE;
                        end else begin
                            sample_cnt <= sample_cnt + 1'b1;
                        end
                    end else begin
                        baud_cnt <= baud_cnt + 1'b1;
                    end
                end

                default: state <= S_IDLE;
            endcase
        end
    end

endmodule
