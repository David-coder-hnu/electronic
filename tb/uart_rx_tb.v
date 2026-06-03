// uart_rx_tb.v — Testbench for uart_rx module
// Tests: normal byte reception, false start rejection, frame error detection

`timescale 1ns / 1ps

module uart_rx_tb;

    reg        clk;
    reg        rst_n;
    reg        rx;
    wire [7:0] rx_byte;
    wire       rx_vld;
    wire       rx_err;

    localparam CLK_PERIOD = 20;  // 50MHz → 20ns
    localparam BAUD_115200_TICK = 8680;  // 1/115200 ≈ 8.68µs = 8680ns (434 clk cycles)

    uart_rx #(
        .CLK_FREQ(50_000_000),
        .BAUD_RATE(115200)
    ) dut (
        .clk(clk), .rst_n(rst_n), .rx(rx),
        .rx_byte(rx_byte), .rx_vld(rx_vld), .rx_err(rx_err)
    );

    initial clk = 0;
    always #(CLK_PERIOD/2) clk = ~clk;

    // UART byte send task: 1 start(L) + 8 data(LSB first) + 1 stop(H)
    task send_byte;
        input [7:0] data;
        integer i;
        begin
            rx = 1'b0;  // Start bit
            #8680;
            for (i = 0; i < 8; i = i + 1) begin
                rx = data[i];  // LSB first
                #8680;
            end
            rx = 1'b1;  // Stop bit
            #8680;
            rx = 1'b1;  // Idle
            #1000;
        end
    endtask

    // TEST 1: Normal byte reception
    initial begin
        $display("=== TEST 1: Normal byte reception ===");
        rst_n = 0; rx = 1'b1;
        #200 rst_n = 1;
        #500;

        send_byte(8'hAA);
        #2000;
        if (rx_vld) $display("PASS: Received 0x%02h (vld=%b err=%b)", rx_byte, rx_vld, rx_err);
        else $display("FAIL: No rx_vld after sending 0xAA");

        send_byte(8'h55);
        #2000;
        if (rx_vld && rx_byte == 8'h55) $display("PASS: Received 0x55");
        else $display("FAIL: Expected 0x55, got 0x%02h", rx_byte);

        send_byte(8'hFF);
        #2000;
        if (rx_vld && rx_byte == 8'hFF) $display("PASS: Received 0xFF");
        else $display("FAIL: Expected 0xFF, got 0x%02h", rx_byte);

        send_byte(8'h00);
        #2000;
        if (rx_vld && rx_byte == 8'h00) $display("PASS: Received 0x00");
        else $display("FAIL: Expected 0x00, got 0x%02h", rx_byte);

        $display("=== TEST 1 Complete ===\n");
    end

    // TEST 2: False start rejection (glitch on RX line)
    initial begin
        #150_000;  // Wait for test 1
        $display("=== TEST 2: False start rejection ===");

        // Generate a short low pulse (~1µs) — should be rejected
        rx = 1'b0;
        #1000;  // Only 50 clock cycles — not a real start bit
        rx = 1'b1;
        #2000;

        // Check no byte was received
        if (!rx_vld) $display("PASS: False start correctly rejected (no rx_vld)");
        else $display("FAIL: False start triggered rx_vld = %b, byte = 0x%02h", rx_vld, rx_byte);

        // Now send a valid byte — should still work after false start
        #5000;
        send_byte(8'h3C);
        #2000;
        if (rx_vld && rx_byte == 8'h3C) $display("PASS: Valid byte received after false start");
        else $display("FAIL: Byte corrupted after false start");

        $display("=== TEST 2 Complete ===\n");
    end

    // TEST 3: Framing error (invalid stop bit)
    initial begin
        #300_000;
        $display("=== TEST 3: Framing error ===");

        // Send a byte with invalid stop bit (low instead of high)
        rx = 1'b0;  // Start
        #8680;
        rx = 1'b1; #8680;  // bit0
        rx = 1'b0; #8680;  // bit1
        rx = 1'b1; #8680;  // bit2
        rx = 1'b0; #8680;  // bit3
        rx = 1'b1; #8680;  // bit4
        rx = 1'b0; #8680;  // bit5
        rx = 1'b1; #8680;  // bit6
        rx = 1'b0; #8680;  // bit7
        rx = 1'b0;  // INVALID stop bit (should be high)
        #8680;
        rx = 1'b1;
        #2000;

        if (rx_err) $display("PASS: Framing error detected (rx_err=%b)", rx_err);
        else $display("FAIL: No framing error detected for bad stop bit");

        $display("=== TEST 3 Complete ===\n");
    end

    // TEST 4: Consecutive bytes (back-to-back)
    initial begin
        #500_000;
        $display("=== TEST 4: Back-to-back bytes ===");
        send_byte(8'hAA);
        send_byte(8'h00);
        send_byte(8'h3F);
        send_byte(8'h00);
        send_byte(8'h00);
        send_byte(8'h95);
        #5000;
        $display("=== TEST 4 Complete ===\n");
    end

    // TEST 5: 0xAA frame header detection
    initial begin
        #700_000;
        $display("=== TEST 5: Frame header (0xAA) sequence ===");
        // Simulate a complete frame: AA 00 3F 00 00 95 (static red)
        send_byte(8'hAA);
        #1000;
        if (rx_vld && rx_byte == 8'hAA) $display("PASS: Frame header 0xAA received");
        else $display("WARN: Frame header 0xAA status: vld=%b byte=0x%02h", rx_vld, rx_byte);

        send_byte(8'h00);  // CMD=static
        send_byte(8'h3F);  // R=max
        send_byte(8'h00);  // G=0
        send_byte(8'h00);  // B=0
        send_byte(8'h95);  // XOR = AA^00^3F^00^00 = 0x95
        #5000;
        $display("=== TEST 5 Complete ===\n");
        $display("=== ALL UART RX TESTS DONE ===");
        $finish;
    end

endmodule
