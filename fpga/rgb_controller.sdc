# ============================================================================
# SDC Timing Constraints — RGB Bluetooth LED Controller
# Quartus II 13.0 TimeQuest format
# Device: Cyclone IV E EP4CE15F17C8, Speed Grade C8
# Clock: 50MHz on-board oscillator
# ============================================================================

# ── Master clock: 50MHz (20ns period) ──
create_clock -name clk_50m -period 20.000 [get_ports clk_50m]

# ── I/O delays ──
# UART RX from CH9143 (external device, no shared clock)
set_input_delay -clock clk_50m -max 5.0 [get_ports pmod_a_rx]
set_input_delay -clock clk_50m -min 0.5 [get_ports pmod_a_rx]

# Reset button (mechanical switch, slow edges, generous constraint)
set_input_delay -clock clk_50m -max 10.0 [get_ports rst_n]
set_input_delay -clock clk_50m -min 0.0  [get_ports rst_n]

# BLESTA status pin from CH9143
set_input_delay -clock clk_50m -max 5.0 [get_ports pmod_a_blesta]
set_input_delay -clock clk_50m -min 0.5 [get_ports pmod_a_blesta]

# UART TX to CH9143 (115200 bps = 8.68us per bit — relaxed)
set_output_delay -clock clk_50m -max 5.0 [get_ports pmod_a_tx]
set_output_delay -clock clk_50m -min 0.5 [get_ports pmod_a_tx]

# PWM outputs to LED board (200Hz — extremely relaxed)
set_output_delay -clock clk_50m -max 5.0 [get_ports pmod_b_io*]
set_output_delay -clock clk_50m -min 0.5 [get_ports pmod_b_io*]

# ── Clock uncertainty — use Quartus built-in estimate ──
# derive_clock_uncertainty reads PLL jitter + board variance from
# the device model. For a 50MHz single-clock design this is sufficient
# and avoids the Quartus-13-era syntax quirks of set_clock_uncertainty.
derive_clock_uncertainty
