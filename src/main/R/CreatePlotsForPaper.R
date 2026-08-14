library(ggplot2)
library(scales)

# -----------------------------------------------------------------------------
# 1. Enter or edit values manually here
#    One row per scenario and cost component.
# -----------------------------------------------------------------------------
costs <- data.frame(
  scenario = rep(c("Basic - ICEV", "Basic - BEV",
                   "Advanced - ICEV", "Advanced - BEV"), each = 5),
  component = rep(c("Fixed vehicle", "Charging infrastructure",
                    "Distance operation", "Distance energy", "Labor"), 4),
  value = c(
    # Basic - ICEV
    1.62, 0.00, 0.29, 0.35, 7.30,
    # Basic - BEV
    1.97, 0.19, 0.23, 0.20, 7.32,
    # Advanced - ICEV
    1.58, 0.00, 0.28, 0.35, 7.14,
    # Advanced - BEV
    1.96, 0.19, 0.23, 0.21, 7.12
  )
)

# Fixed order of bars and stacked components
costs$scenario <- factor(
  costs$scenario,
  levels = c("Basic - ICEV", "Basic - BEV",
             "Advanced - ICEV", "Advanced - BEV")
)

# The first factor level is drawn at the bottom of each bar.
costs$component <- factor(
  costs$component,
  levels = c("Labor", "Distance energy", "Distance operation",
             "Charging infrastructure", "Fixed vehicle")
)

# Text labels within segments; do not label zero values.
# Increase the threshold if labels in very small segments become too crowded.
label_threshold <- 0.001
costs$label <- ifelse(
  costs$value >= label_threshold,
  number(costs$value, accuracy = 0.01, decimal.mark = "."),
  ""
)

# Totals calculated from the manually entered component values
totals <- aggregate(value ~ scenario, data = costs, FUN = sum)

# -----------------------------------------------------------------------------
# 2. Create plot
# -----------------------------------------------------------------------------
p_stacked <- ggplot(costs, aes(x = scenario, y = value, fill = component)) +
  geom_col(width = 0.72, color = "white", linewidth = 0.35) +
  geom_text(
    aes(label = label),
    position = position_stack(vjust = 0.5),
    color = "white",
    fontface = "bold",
    size = 3.6
  ) +
  geom_text(
    data = totals,
    aes(x = scenario, y = value, label = number(value, accuracy = 0.01)),
    inherit.aes = FALSE,
    vjust = -0.6,
    fontface = "bold",
    size = 4
  ) +
  scale_fill_manual(
    values = c(
      "Labor"                   = "#2F5597",
      "Distance energy"         = "#70AD47",
      "Distance operation"      = "#ED7D31",
      "Charging infrastructure" = "#A5A5A5",
      "Fixed vehicle"           = "#5B9BD5"
    )
  ) +
  scale_y_continuous(
    name = "Comparable operating costs [million EUR/day]",
    labels = label_number(accuracy = 1),
    expand = expansion(mult = c(0, 0.10))
  ) +
  scale_x_discrete(
    name = NULL,
    labels = c(
      "Basic - ICEV"    = "Basic\nICEV",
      "Basic - BEV"     = "Basic\nBEV",
      "Advanced - ICEV" = "Advanced\nICEV",
      "Advanced - BEV"  = "Advanced\nBEV"
    )
  ) +
  labs(
    title = "Composition of comparable operating costs",
    subtitle = "2024 full-fleet electrification comparison",
    fill = "Cost component",
    caption = "Values in million EUR per simulated average working day (10% sample)."
  ) +
  coord_cartesian(clip = "off") +
  theme_minimal(base_size = 12) +
  theme(
    plot.title = element_text(face = "bold", size = 16),
    plot.subtitle = element_text(color = "grey35"),
    panel.grid.major.x = element_blank(),
    panel.grid.minor = element_blank(),
    axis.text.x = element_text(face = "bold", color = "grey20"),
    axis.title.y = element_text(face = "bold"),
    legend.position = "bottom",
    legend.title = element_text(face = "bold"),
    plot.margin = margin(10, 15, 10, 10)
  )

# Display in RStudio
print(p_stacked)

# Optional export (uncomment if desired)
# ggsave("stacked_operating_costs.png", p_stacked, width = 10, height = 6,
#        units = "in", dpi = 300, bg = "white")
# ggsave("stacked_operating_costs.pdf", p_stacked, width = 10, height = 6,
#        units = "in", bg = "white")


# =============================================================================
# FIGURE 2: Total costs and average costs per tour
# =============================================================================

# -----------------------------------------------------------------------------
# 3. Enter or edit the values for the line plot here
# -----------------------------------------------------------------------------
line_costs <- data.frame(
  demand = rep(c("Basic", "Basic", "Advanced", "Advanced"), each = 3),
  vehicle = rep(c("ICEV", "BEV", "ICEV", "BEV"), each = 3),
  year = rep(c(2024, 2030, 2050), 4),
  total_cost = c(
    9546693, 9724210, 9950986,  # Basic - ICEV
    9922681, 9893046, 9893023,  # Basic - BEV
    9356211, 9536415, 9761765,  # Advanced - ICEV
    9704436, 9674549, 9674549   # Advanced - BEV
  ),
  cost_per_tour = c(
    323.6, 329.6, 337.3,  # Basic - ICEV
    328.2, 327.2, 327.2,  # Basic - BEV
    324.7, 330.9, 338.7,  # Advanced - ICEV
    328.6, 327.5, 327.5   # Advanced - BEV
  )
)

line_costs$scenario <- factor(
  paste(line_costs$demand, line_costs$vehicle, sep = " - "),
  levels = c("Basic - ICEV", "Basic - BEV",
             "Advanced - ICEV", "Advanced - BEV")
)

demand_colors <- c(
  "Basic" = "#2F5597",
  "Advanced" = "#ED7D31"
)

# -----------------------------------------------------------------------------
# 4. Create line plot for total costs
# -----------------------------------------------------------------------------
p_total <- ggplot(
  line_costs,
  aes(
    x = year,
    color = demand,
    linetype = vehicle,
    group = interaction(demand, vehicle)
  )
) +
  geom_line(aes(y = total_cost), linewidth = 1.1) +
  geom_point(aes(y = total_cost), size = 3) +
  geom_text(
    aes(
      y = total_cost,
      label = number(total_cost / 1e6, accuracy = 0.01),
      vjust = ifelse(vehicle == "ICEV", -0.85, 1.35)
    ),
    size = 3.5,
    show.legend = FALSE
  ) +
  scale_x_continuous(
    name = "Energy-price year",
    breaks = c(2024, 2030, 2050)
  ) +
  scale_y_continuous(
    name = "Comparable total cost [million EUR/day]",
    labels = label_number(scale = 1e-6, accuracy = 0.1),
    expand = expansion(mult = c(0.08, 0.10))
  ) +
  scale_color_manual(values = demand_colors) +
  scale_linetype_manual(
    values = c("ICEV" = "solid", "BEV" = "dashed")
  ) +
  labs(
    title = "Comparable total costs",
    subtitle = "2024, 2030, and 2050 energy-price settings",
    color = "Demand generation",
    linetype = "Vehicle type"
  ) +
  theme_minimal(base_size = 12) +
  theme(
    plot.title = element_text(face = "bold", size = 16),
    plot.subtitle = element_text(color = "grey35"),
    axis.title.x = element_text(face = "bold"),
    axis.title.y = element_text(face = "bold"),
    panel.grid.minor = element_blank(),
    legend.position = "bottom",
    legend.title = element_text(face = "bold"),
    plot.margin = margin(10, 15, 10, 15)
  )

# -----------------------------------------------------------------------------
# 5. Create line plot for average costs per tour
# -----------------------------------------------------------------------------
p_per_tour <- ggplot(
  line_costs,
  aes(
    x = year,
    y = cost_per_tour,
    color = demand,
    linetype = vehicle,
    group = interaction(demand, vehicle)
  )
) +
  geom_line(linewidth = 1.1) +
  geom_point(size = 3) +
  ggrepel::geom_text_repel(
    aes(label = number(cost_per_tour, accuracy = 0.1)),
    size = 3.5,
    direction = "y",
    box.padding = 0.35,
    point.padding = 0.25,
    min.segment.length = 0,
    segment.color = "grey60",
    show.legend = FALSE,
    seed = 123
  ) +
  scale_x_continuous(
    name = "Energy-price year",
    breaks = c(2024, 2030, 2050)
  ) +
  scale_y_continuous(
    name = "Average comparable cost per tour [EUR]",
    labels = label_number(accuracy = 1),
    expand = expansion(mult = c(0.12, 0.15))
  ) +
  scale_color_manual(values = demand_colors) +
  scale_linetype_manual(
    values = c("ICEV" = "solid", "BEV" = "dashed")
  ) +
  labs(
    title = "Average comparable costs per tour",
    subtitle = "2024, 2030, and 2050 energy-price settings",
    color = "Demand generation",
    linetype = "Vehicle type"
  ) +
  theme_minimal(base_size = 12) +
  theme(
    plot.title = element_text(face = "bold", size = 16),
    plot.subtitle = element_text(color = "grey35"),
    axis.title.x = element_text(face = "bold"),
    axis.title.y = element_text(face = "bold"),
    panel.grid.minor = element_blank(),
    legend.position = "bottom",
    legend.title = element_text(face = "bold"),
    plot.margin = margin(10, 15, 10, 15)
  )

# Display both line plots in RStudio
print(p_total)
print(p_per_tour)

# Optional export (uncomment if desired)
# ggsave("total_cost_lineplot.png", p_total, width = 10, height = 6,
#        units = "in", dpi = 300, bg = "white")
# ggsave("cost_per_tour_lineplot.png", p_per_tour, width = 10, height = 6,
#        units = "in", dpi = 300, bg = "white")
# ggsave("total_cost_lineplot.pdf", p_total, width = 10, height = 6,
#        units = "in", bg = "white")
# ggsave("cost_per_tour_lineplot.pdf", p_per_tour, width = 10, height = 6,
#        units = "in", bg = "white")