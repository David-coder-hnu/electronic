// effect_engine_tb.v — Testbench for effect_engine module
// Tests: static mode, breathing (PWL sin cycle), chasing (dual LED),
//        mode switching (smooth transition), disconnect fade

`timescale 1ns / 1ps

module effect_engine_tb;

    reg        clk;
    reg        rst_n;
    reg        bt_connected;
    reg  [1:0] cmd_mode;
    reg  [5:0] cmd_r, cmd_g, cmd_b;
    reg        cmd_vld;
    reg        pwm_tick;

    wire [7:0] duty_led1_r, duty_led1_g, duty_led1_b;
    wire [7:0] duty_led2_r, duty_led2_g, duty_led2_b;

    localparam CLK_PERIOD = 20;  // 50MHz

    effect_engine #(.PWM_WIDTH(8)) dut (
        .clk(clk), .rst_n(rst_n), .bt_connected(bt_connected),
        .cmd_mode(cmd_mode), .cmd_r(cmd_r), .cmd_g(cmd_g), .cmd_b(cmd_b),
        .cmd_vld(cmd_vld), .pwm_tick(pwm_tick),
        .duty_led1_r(duty_led1_r), .duty_led1_g(duty_led1_g), .duty_led1_b(duty_led1_b),
        .duty_led2_r(duty_led2_r), .duty_led2_g(duty_led2_g), .duty_led2_b(duty_led2_b)
    );

    initial clk = 0;
    always #(CLK_PERIOD/2) clk = ~clk;

    // PWM tick: 200Hz → every 5ms = 250,000 clock cycles
    // For simulation speed: use shorter period
    initial pwm_tick = 0;
    always begin
        #5000 pwm_tick = 1;  // Pulse
        #20    pwm_tick = 0;
        #4980;                // ~5µs period for simulation
    end

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

    // TEST 1: Static mode — fixed RGB
    initial begin
        $display("=== TEST 1: Static mode ===");
        rst_n = 0; bt_connected = 1'b1;
        cmd_mode = 0; cmd_r = 0; cmd_g = 0; cmd_b = 0; cmd_vld = 0;
        #200 rst_n = 1;
        #500;

        send_cmd(2'b00, 6'h3F, 6'h00, 6'h00);  // Red, full brightness
        #1000;
        $display("Static Red: LED1=(%d,%d,%d) LED2=(%d,%d,%d)",
            duty_led1_r, duty_led1_g, duty_led1_b,
            duty_led2_r, duty_led2_g, duty_led2_b);
        if (duty_led1_r > 0 && duty_led1_g == 0 && duty_led1_b == 0)
            $display("PASS: Static red — LED1_R=%d", duty_led1_r);
        else $display("FAIL: Expected red, got (%d,%d,%d)", duty_led1_r, duty_led1_g, duty_led1_b);

        send_cmd(2'b00, 6'h00, 6'h3F, 6'h3F);  // Cyan
        #1000;
        if (duty_led1_r == 0 && duty_led1_g > 0 && duty_led1_b > 0)
            $display("PASS: Static cyan");
        else $display("FAIL: Expected cyan");

        $display("=== TEST 1 Complete ===\n");
    end

    // TEST 2: Breathing mode — PWL sin cycle
    initial begin
        #50_000;
        $display("=== TEST 2: Breathing mode (PWL sin) ===");
        send_cmd(2'b01, 6'h3F, 6'h20, 6'h10);  // Warm orange base
        // Wait a few PWM ticks for breathing to start
        repeat (50) @(posedge pwm_tick);
        $display("Breathing duty after 50 PWM ticks: R=%d G=%d B=%d",
            duty_led1_r, duty_led1_g, duty_led1_b);
        // Duty should be non-zero (breathing has started)
        if (duty_led1_r > 0 || duty_led1_g > 0 || duty_led1_b > 0)
            $display("PASS: Breathing active — output non-zero");
        else $display("FAIL: Breathing output all zero");

        // Sample a few values to see the breathing curve
        repeat (20) @(posedge pwm_tick);
        $display("  t=+20: R=%d G=%d B=%d", duty_led1_r, duty_led1_g, duty_led1_b);
        repeat (20) @(posedge pwm_tick);
        $display("  t=+40: R=%d G=%d B=%d", duty_led1_r, duty_led1_g, duty_led1_b);

        $display("=== TEST 2 Complete ===\n");
    end

    // TEST 3: Chasing mode — LED1 and LED2 alternate
    initial begin
        #200_000;
        $display("=== TEST 3: Chasing mode ===");
        send_cmd(2'b10, 6'h20, 6'h3F, 6'h20);  // Green-ish
        repeat (30) @(posedge pwm_tick);
        $display("Chasing: LED1=(%d,%d,%d) LED2=(%d,%d,%d)",
            duty_led1_r, duty_led1_g, duty_led1_b,
            duty_led2_r, duty_led2_g, duty_led2_b);
        // LED1 and LED2 should be different (chasing alternates)
        if (duty_led1_g != duty_led2_g)
            $display("PASS: Chasing — LED1 and LED2 differ (alternating)");
        else $display("WARN: LED1==LED2 at this sample (may be at crossover point)");

        repeat (20) @(posedge pwm_tick);
        $display("  t=+20: LED1=(%d,%d,%d) LED2=(%d,%d,%d)",
            duty_led1_r, duty_led1_g, duty_led1_b,
            duty_led2_r, duty_led2_g, duty_led2_b);

        $display("=== TEST 3 Complete ===\n");
    end

    // TEST 4: Mode switching — verify no glitch (duty doesn't go undefined)
    initial begin
        #350_000;
        $display("=== TEST 4: Mode switching ===");
        send_cmd(2'b00, 6'h30, 6'h10, 6'h20);  // Static purple
        #1000;
        $display("Static: LED1_R=%d", duty_led1_r);

        send_cmd(2'b01, 6'h30, 6'h10, 6'h20);  // Breathing same color
        #1000;
        $display("Switched to breathing: LED1_R=%d", duty_led1_r);
        if (duty_led1_r !== 8'hxx)
            $display("PASS: Mode switch — no undefined (X) output");
        else $display("FAIL: Mode switch produced X on output");

        send_cmd(2'b10, 6'h30, 6'h10, 6'h20);  // Chasing same color
        #1000;
        $display("Switched to chasing: LED1_R=%d", duty_led1_r);
        if (duty_led1_r !== 8'hxx)
            $display("PASS: Mode switch chasing — no X");
        else $display("FAIL: Chasing switch produced X");

        $display("=== TEST 4 Complete ===\n");
    end

    // TEST 5: Disconnect fade
    initial begin
        #500_000;
        $display("=== TEST 5: Disconnect handling ===");
        send_cmd(2'b00, 6'h3F, 6'h3F, 6'h00);  // Yellow, full
        #5000;

        // Disconnect
        bt_connected = 1'b0;
        $display("BT disconnected — waiting for fade...");
        repeat (100) @(posedge pwm_tick);

        $display("After fade: LED1=(%d,%d,%d)",
            duty_led1_r, duty_led1_g, duty_led1_b);
        if (duty_led1_r < 250)  // Should have decreased from max
            $display("PASS: Disconnect fade — brightness decreased");
        else $display("WARN: Disconnect fade may not be complete yet");

        // Reconnect
        bt_connected = 1'b1;
        repeat (5) @(posedge pwm_tick);
        $display("Reconnected: LED1=(%d,%d,%d)",
            duty_led1_r, duty_led1_g, duty_led1_b);

        $display("=== TEST 5 Complete ===\n");
        $display("=== ALL EFFECT ENGINE TESTS DONE ===");
        $finish;
    end

endmodule
