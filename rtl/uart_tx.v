// uart_tx.v — UART Transmitter for status feedback (0xBB frames)
// Baud: 115200 @ 50MHz, 8N1 format

module uart_tx #(
    parameter CLK_FREQ  = 50_000_000,
    parameter BAUD_RATE = 115200
) (
    input  wire       clk,
    input  wire       rst_n,
    input  wire [7:0] tx_byte,     // Byte to send
    input  wire       tx_start,     // Pulse: start transmission
    output reg        tx,           // UART TX output
    output reg        tx_busy       // High while transmitting
);

    localparam BIT_CNT_MAX = CLK_FREQ / BAUD_RATE;  // ≈ 434

    reg [$clog2(BIT_CNT_MAX)-1:0] bit_timer;
    reg [3:0]                      bit_idx;    // 0=start, 1-8=data, 9=stop
    reg [7:0]                      tx_data;

    localparam S_IDLE  = 2'd0;
    localparam S_SEND  = 2'd1;
    localparam S_DONE  = 2'd2;

    reg [1:0] state;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state     <= S_IDLE;
            tx        <= 1'b1;     // Idle high
            tx_busy   <= 1'b0;
            bit_timer <= 'd0;
            bit_idx   <= 'd0;
            tx_data   <= 8'd0;
        end else begin
            case (state)
                S_IDLE: begin
                    tx        <= 1'b1;
                    tx_busy   <= 1'b0;
                    if (tx_start) begin
                        tx_data  <= tx_byte;
                        state    <= S_SEND;
                        tx_busy  <= 1'b1;
                        bit_timer <= 'd0;
                        bit_idx   <= 'd0;
                        tx        <= 1'b0;  // Start bit
                    end
                end

                S_SEND: begin
                    if (bit_timer == BIT_CNT_MAX - 1) begin
                        bit_timer <= 'd0;
                        bit_idx   <= bit_idx + 1'b1;
                        case (bit_idx)
                            4'd0,4'd1,4'd2,4'd3,4'd4,4'd5,4'd6,4'd7:
                                tx <= tx_data[bit_idx];   // Data bits (LSB first)
                            4'd8: begin
                                tx    <= 1'b1;             // Stop bit
                                state <= S_DONE;
                            end
                            default: tx <= 1'b1;
                        endcase
                    end else begin
                        bit_timer <= bit_timer + 1'b1;
                    end
                end

                S_DONE: begin
                    tx_busy <= 1'b0;
                    tx      <= 1'b1;
                    state   <= S_IDLE;
                end

                default: state <= S_IDLE;
            endcase
        end
    end

endmodule
