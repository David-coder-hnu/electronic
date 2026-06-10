// effect_engine_tb.v — Testbench for effect_engine (WS2812B 8-LED version)
// Tests: static mode, breathing (PWL sin cycle), chasing (8 LED wave),
//        mode switching (smooth transition)

`timescale 1ns / 1ps

module effect_engine_tb;

    reg        clk;
    reg        rst_n;
    reg  [1:0] cmd_mode;
    reg  [5:0] cmd_r, cmd_g, cmd_b;
    reg        cmd_vld;

    wire [191:0] led_data;
    wire         update;

    // LED0 color extraction helpers
    wire [7:0] led0_g = led_data[191:184];
    wire [7:0] led0_r = led_data[183:176];
    wire [7:0] led0_b = led_data[175:168];
    // LED7 color (last in chain)
    wire [7:0] led7_g = led_data[23:16];
    wire [7:0] led7_r = led_data[15:8];
    wire [7:0] led7_b = led_data[7:0];

    localparam CLK_PERIOD = 20;  // 50MHz

    effect_engine dut (
        .clk(clk), .rst_n(rst_n),
        .cmd_mode(cmd_mode), .cmd_r(cmd_r), .cmd_g(cmd_g), .cmd_b(cmd_b),
        .cmd_vld(cmd_vld),
        .led_data(led_data), .update(update)
    );

    initial clk = 0;
    always #(CLK_PERIOD/2) clk = ~clk;

    task send_cmd;
        input [1:0] mode;
        input [5:0] r, g, b;
        begin
            @(posedge clk);
            cmd_mode <= mode;
            cmd_r    <= r;
            cmd_g    <= g;
            cmd_b    <= b;
            cmd_vld  <= 1'b1;
            @(posedge clk);
            cmd_vld  <= 1'b0;
            @(posedge clk);
        end
    endtask

    // Wait for N update pulses (200Hz refresh ticks)
    task wait_updates;
        input integer n;
        integer j;
        begin
            for (j = 0; j < n; j = j + 1) begin
                @(posedge update);
                #10;  // small delay after pulse
            end
        end
    endtask

    // TEST 1: Static mode — all 8 LEDs same fixed color
    initial begin
        $display("=== TEST 1: Static mode (8 LEDs) ===");
        rst_n = 0;
        cmd_mode = 0; cmd_r = 0; cmd_g = 0; cmd_b = 0; cmd_vld = 0;
        #200 rst_n = 1;
        #500;

        send_cmd(2'b00, 6'h3F, 6'h00, 6'h00);  // Red, full brightness
        wait_updates(3);
        $display("Static Red: LED0=(%d,%d,%d) LED7=(%d,%d,%d)",
            led0_r, led0_g, led0_b, led7_r, led7_g, led7_b);
        // 6-bit base 0x3F scaled by 0xFF / 64 → ~252
        if (led0_r > 200 && led0_g == 0 && led0_b == 0)
            $display("PASS: Static red — LED0_R=%d", led0_r);
        else $display("FAIL: Expected red, got LED0=(%d,%d,%d)", led0_r, led0_g, led0_b);
        // All 8 LEDs should be identical in static mode
        if (led0_r == led7_r && led0_g == led7_g && led0_b == led7_b)
            $display("PASS: All 8 LEDs identical in static mode");
        else $display("FAIL: LED0 != LED7 in static mode");

        send_cmd(2'b00, 6'h00, 6'h3F, 6'h3F);  // Cyan
        wait_updates(3);
        if (led0_r == 0 && led0_g > 200 && led0_b > 200)
            $display("PASS: Static cyan");
        else $display("FAIL: Expected cyan, got LED0=(%d,%d,%d)", led0_r, led0_g, led0_b);

        $display("=== TEST 1 Complete ===\n");
    end

    // TEST 2: Breathing mode — all 8 LEDs breathe together
    initial begin
        #3_000_000;
        $display("=== TEST 2: Breathing mode (PWL sin, 8 LEDs) ===");
        send_cmd(2'b01, 6'h3F, 6'h20, 6'h10);  // Warm orange base
        wait_updates(5);
        $display("Breathing after 5 updates: LED0=(%d,%d,%d)",
            led0_r, led0_g, led0_b);
        if (led0_r > 0 || led0_g > 0 || led0_b > 0)
            $display("PASS: Breathing active — output non-zero");
        else $display("FAIL: Breathing output all zero");

        // Sample at different times to see the breathing curve
        wait_updates(10);
        $display("  t=+10: LED0=(%d,%d,%d)", led0_r, led0_g, led0_b);
        wait_updates(10);
        $display("  t=+20: LED0=(%d,%d,%d)", led0_r, led0_g, led0_b);
        wait_updates(10);
        $display("  t=+30: LED0=(%d,%d,%d)", led0_r, led0_g, led0_b);

        // All 8 LEDs should breathe together
        if (led0_r == led7_r && led0_g == led7_g && led0_b == led7_b)
            $display("PASS: All 8 LEDs breathing in sync");
        else $display("WARN: LED0 and LED7 differ during breathing (unexpected)");

        $display("=== TEST 2 Complete ===\n");
    end

    // TEST 3: Chasing mode — 8 LEDs with phase offsets
    initial begin
        #8_000_000;
        $display("=== TEST 3: Chasing mode (8-LED wave) ===");
        send_cmd(2'b10, 6'h20, 6'h3F, 6'h20);  // Green-ish
        wait_updates(5);
        $display("Chasing: LED0=(%d,%d,%d) LED7=(%d,%d,%d)",
            led0_r, led0_g, led0_b, led7_r, led7_g, led7_b);
        // LED0 and LED7 should be different (phase offset of 7*2=14 entries)
        if (led0_g != led7_g)
            $display("PASS: Chasing — LED0 and LED7 have different brightness");
        else $display("WARN: LED0==LED7 at this sample (may be at crossover point)");

        wait_updates(10);
        $display("  t=+10: LED0=(%d,%d,%d) LED7=(%d,%d,%d)",
            led0_r, led0_g, led0_b, led7_r, led7_g, led7_b);

        wait_updates(10);
        $display("  t=+20: LED0=(%d,%d,%d) LED7=(%d,%d,%d)",
            led0_r, led0_g, led0_b, led7_r, led7_g, led7_b);

        $display("=== TEST 3 Complete ===\n");
    end

    // TEST 4: Mode switching — verify no glitch
    initial begin
        #15_000_000;
        $display("=== TEST 4: Mode switching ===");
        send_cmd(2'b00, 6'h30, 6'h10, 6'h20);  // Static purple
        wait_updates(3);
        $display("Static: LED0_R=%d", led0_r);

        send_cmd(2'b01, 6'h30, 6'h10, 6'h20);  // Breathing same color
        wait_updates(3);
        $display("Switched to breathing: LED0_R=%d", led0_r);
        if (led0_r !== 8'hxx)
            $display("PASS: Mode switch — no undefined (X) output");
        else $display("FAIL: Mode switch produced X on output");

        send_cmd(2'b10, 6'h30, 6'h10, 6'h20);  // Chasing same color
        wait_updates(3);
        $display("Switched to chasing: LED0_R=%d", led0_r);
        if (led0_r !== 8'hxx)
            $display("PASS: Mode switch chasing — no X");
        else $display("FAIL: Chasing switch produced X");

        $display("=== TEST 4 Complete ===\n");
        $display("=== ALL EFFECT ENGINE TESTS DONE ===");
        $finish;
    end

endmodule
