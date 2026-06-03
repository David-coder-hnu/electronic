# ============================================================================
# SDC Timing Constraints — RGB Bluetooth LED Controller
# Device: Cyclone IV E EP4CE15F17C8, Speed Grade C8
# Clock: 50MHz on-board oscillator
# ============================================================================

# ── Master clock: 50MHz (20ns period) ──
create_clock -name clk_50m -period 20.000 [get_ports {clk_50m}]

# ── Input delay constraints ──
# UART RX from CH9143 (external device, no shared clock)
# Conservative: model as 5ns max input delay
set_input_delay -clock clk_50m -max 5.0 [get_ports {pmod_a_rx}]
set_input_delay -clock clk_50m -min 0.5 [get_ports {pmod_a_rx}]

# Reset button (mechanical switch, slow edges)
# Use generous constraints — button presses are human-speed
set_input_delay -clock clk_50m -max 10.0 [get_ports {rst_n}]
set_input_delay -clock clk_50m -min 0.0  [get_ports {rst_n}]

# BLESTA status pin from CH9143
set_input_delay -clock clk_50m -max 5.0  [get_ports {pmod_a_blesta}]
set_input_delay -clock clk_50m -min 0.5  [get_ports {pmod_a_blesta}]

# ── Output delay constraints ──
# UART TX to CH9143 — relaxed (115200 bps = 8.68us bit period)
set_output_delay -clock clk_50m -max 5.0 [get_ports {pmod_a_tx}]
set_output_delay -clock clk_50m -min 0.5 [get_ports {pmod_a_tx}]

# PWM outputs to LED board — relaxed (200Hz, human eye can't see ns jitter)
set_output_delay -clock clk_50m -max 5.0 [get_ports {pmod_b_io*}]
set_output_delay -clock clk_50m -min 0.5 [get_ports {pmod_b_io*}]

# ── Clock uncertainty ──
# Typical Cyclone IV PLL jitter + board-level uncertainty
set_clock_uncertainty -setup 0.200 [get_clocks {clk_50m}]
set_clock_uncertainty -hold  0.100 [get_clocks {clk_50m}]

# ── Derive PLL clocks (if PLL is added later for audio FFT) ──
# derive_pll_clocks
# derive_clock_uncertainty
