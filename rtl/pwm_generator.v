// pwm_generator.v — Parameterized PWM Generator with double-buffered duty update
// Generates PWM output at PWM_FREQ with WIDTH-bit resolution
// Double-buffering: new duty takes effect at start of next PWM cycle → no glitches

module pwm_generator #(
    parameter WIDTH    = 8,
    parameter CLK_FREQ = 50_000_000,
    parameter PWM_FREQ = 200
) (
    input  wire                    clk,
    input  wire                    rst_n,
    input  wire [WIDTH-1:0]        duty_in,     // Target duty cycle
    input  wire                    duty_vld,    // Duty update strobe
    output wire                    pwm_out,     // PWM output
    output reg                     pwm_tick     // Pulses at PWM frequency (for timebase)
);

    localparam CNT_MAX = CLK_FREQ / (PWM_FREQ * (1 << WIDTH));  // Clock ticks per PWM step
    // Example: 50M / (200 * 256) = 50M / 51200 = 976

    reg [$clog2(CNT_MAX)-1:0] prescale_cnt;
    reg [WIDTH-1:0]           pwm_cnt;
    reg [WIDTH-1:0]           duty_shadow;  // Shadow register — updated at cycle boundary

    // Prescaler + PWM counter
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            prescale_cnt <= 'd0;
            pwm_cnt      <= {WIDTH{1'b0}};
            pwm_tick     <= 1'b0;
        end else begin
            pwm_tick <= 1'b0;
            if (prescale_cnt == CNT_MAX - 1) begin
                prescale_cnt <= 'd0;
                if (pwm_cnt == {WIDTH{1'b1}}) begin
                    pwm_cnt  <= {WIDTH{1'b0}};
                    pwm_tick <= 1'b1;
                    // Latch new duty at PWM cycle boundary — double buffering
                    if (duty_vld) duty_shadow <= duty_in;
                end else begin
                    pwm_cnt <= pwm_cnt + 1'b1;
                end
            end else begin
                prescale_cnt <= prescale_cnt + 1'b1;
            end
        end
    end

    // PWM comparator
    assign pwm_out = (pwm_cnt < duty_shadow) ? 1'b1 : 1'b0;

endmodule
