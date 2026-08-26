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
costs$scenario_id <- as.numeric(costs$scenario)

# The last factor level is drawn at the bottom of each bar.
costs$component <- factor(
  costs$component,
  levels = c("Labor", "Distance energy", "Distance operation",
             "Charging infrastructure", "Fixed vehicle")
)

# Text labels within segments; do not label zero values.
# Draw small values outside the bars to keep them readable in a compact figure.
small_label_threshold <- 0.40
costs$label_inside <- ifelse(
  costs$value >= small_label_threshold,
  number(costs$value, accuracy = 0.01, decimal.mark = "."),
  ""
)
costs$label_outside <- ifelse(
  costs$value > 0 & costs$value < small_label_threshold,
  number(costs$value, accuracy = 0.01, decimal.mark = "."),
  ""
)

# Midpoints of stacked segments for labels drawn outside the bars.
stack_order <- rev(levels(costs$component))
costs$stack_order <- match(as.character(costs$component), stack_order)
costs <- costs[order(costs$scenario, costs$stack_order), ]
costs$ymin <- ave(costs$value, costs$scenario, FUN = function(x) cumsum(x) - x)
costs$ymax <- ave(costs$value, costs$scenario, FUN = cumsum)
costs$y_mid <- (costs$ymin + costs$ymax) / 2
small_labels <- subset(costs, label_outside != "")

spread_label_y <- function(y, min_gap = 0.32) {
  if (length(y) <= 1) {
    return(y)
  }

  order_y <- order(y)
  placed <- y[order_y]

  for (i in 2:length(placed)) {
    placed[i] <- max(placed[i], placed[i - 1] + min_gap)
  }

  placed <- placed - (mean(placed) - mean(y[order_y]))

  for (i in 2:length(placed)) {
    placed[i] <- max(placed[i], placed[i - 1] + min_gap)
  }

  adjusted <- y
  adjusted[order_y] <- placed
  adjusted
}

small_labels$label_y <- ave(
  small_labels$y_mid,
  small_labels$scenario,
  FUN = spread_label_y
)

# Totals calculated from the manually entered component values
totals <- aggregate(value ~ scenario, data = costs, FUN = sum)
totals$scenario_id <- as.numeric(totals$scenario)

# -----------------------------------------------------------------------------
# 2. Create plot
# -----------------------------------------------------------------------------
p_stacked <- ggplot(costs, aes(x = scenario_id, y = value, fill = component)) +
  geom_col(width = 0.64, color = "white", linewidth = 0.25) +
  geom_text(
    aes(label = label_inside),
    position = position_stack(vjust = 0.5),
    color = "white",
    fontface = "bold",
    size = 2.4
  ) +
  geom_segment(
    data = small_labels,
    aes(
      x = scenario_id + 0.33,
      xend = scenario_id + 0.43,
      y = y_mid,
      yend = label_y
    ),
    inherit.aes = FALSE,
    color = "grey45",
    linewidth = 0.2
  ) +
  geom_text(
    data = small_labels,
    aes(x = scenario_id + 0.46, y = label_y, label = label_outside),
    inherit.aes = FALSE,
    hjust = 0,
    size = 2.4,
    color = "grey20"
  ) +
  geom_text(
    data = totals,
    aes(x = scenario_id, y = value, label = number(value, accuracy = 0.01)),
    inherit.aes = FALSE,
    vjust = -0.35,
    fontface = "bold",
    size = 3.0
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
    expand = expansion(mult = c(0, 0.06))
  ) +
  scale_x_continuous(
    name = NULL,
    breaks = seq_along(levels(costs$scenario)),
    limits = c(0.55, 5.05),
    expand = expansion(mult = 0),
    labels = c(
      "Basic\nICEV",
      "Basic\nBEV",
      "Advanced\nICEV",
      "Advanced\nBEV"
    )
  ) +
  labs(
    title = NULL,
    subtitle = NULL,
    fill = "Cost component",
    caption = NULL
  ) +
  coord_cartesian(clip = "off") +
  guides(fill = guide_legend(ncol = 1, byrow = TRUE)) +
  theme_minimal(base_size = 9) +
  theme(
    panel.grid.major.x = element_blank(),
    panel.grid.minor = element_blank(),
    axis.text.x = element_text(face = "bold", color = "grey20", size = 8),
    axis.text.y = element_text(size = 8),
    axis.title.y = element_text(face = "bold", size = 8),
    legend.position = "right",
    legend.text = element_text(size = 7),
    legend.key.size = grid::unit(3, "mm"),
    legend.spacing.y = grid::unit(1.0, "mm"),
    plot.margin = margin(2, 2, 2, 2)
  )

# Display in RStudio
print(p_stacked)

# Optional export (uncomment if desired)
# ggsave("stacked_operating_costs.png", p_stacked, width = 7.0, height = 2.6,
#        units = "in", dpi = 300, bg = "white")
# ggsave("stacked_operating_costs.pdf", p_stacked, width = 7.0, height = 2.6,
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


line_BEV_costs_benefits <- data.frame(
  demand = rep(c("Advanced"), each = 18),
  component = rep(c("commercialPersonTraffic", "smallScaleGoodsTraffic", "wasteCollection", "CEP", "remainingLTL", "Comparable total"), each = 3),
  year = rep(c(2024, 2030, 2050), 6),
  share_tours = c(
    9.8, 26.8, 45.2,  # Advanced - commercialPersonTraffic
    29.5, 58.6, 68.2,  # Advanced - smallScaleGoodsTraffic
    2.1, 31.2, 75.0,  # Advanced - wasteCollection
    27.6, 62.7, 88.6,  # Advanced - CEP
    39.4, 83.3, 94.8,  # Advanced - remainingLTL
    16.3, 37.4, 53.3   # Advanced - Comparable total
  ),
  share_distances = c(
    27.1, 56.5, 77.4,  # Advanced - commercialPersonTraffic
    56.5, 87.1, 93.4,  # Advanced - smallScaleGoodsTraffic
    4.0, 41.5, 83.2,  # Advanced - wasteCollection
    48.5, 81.7, 95.8,  # Advanced - CEP
    53.0, 93.6, 98.8,  # Advanced - remainingLTL
    38.6, 68.8, 83.9   # Advanced - Comparable total
  )
)

line_costs$scenario <- factor(
  paste(line_costs$demand, line_costs$vehicle, sep = " - "),
  levels = c("Basic - ICEV", "Basic - BEV",
             "Advanced - ICEV", "Advanced - BEV")
)

line_BEV_costs_benefits$component <- factor(
  line_BEV_costs_benefits$component,
  levels = c(
    "commercialPersonTraffic",
    "smallScaleGoodsTraffic",
    "wasteCollection",
    "CEP",
    "remainingLTL",
    "Comparable total"
  )
)

bev_plot_data <- data.frame(
  demand = rep(line_BEV_costs_benefits$demand, times = 2),
  component = rep(line_BEV_costs_benefits$component, times = 2),
  year = rep(line_BEV_costs_benefits$year, times = 2),
  share_type = factor(
    rep(c("Tours", "Distance"), each = nrow(line_BEV_costs_benefits)),
    levels = c("Tours", "Distance")
  ),
  share = c(
    line_BEV_costs_benefits$share_tours,
    line_BEV_costs_benefits$share_distances
  )
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

get_plot_legend <- function(plot) {
  plot_gtable <- ggplotGrob(plot)
  guide_index <- grep("^guide-box", plot_gtable$layout$name)
  guide_index <- guide_index[
    !vapply(plot_gtable$grobs[guide_index], inherits, logical(1), "zeroGrob")
  ]

  if (length(guide_index) == 0) {
    return(NULL)
  }

  plot_gtable$grobs[[guide_index[1]]]
}

p_costs_legend_source <- p_total +
  guides(
    color = guide_legend(order = 1),
    linetype = guide_legend(
      order = 2,
      override.aes = list(linewidth = 1.2)
    )
  ) +
  theme(
    legend.position = "bottom",
    legend.box = "horizontal",
    legend.title = element_text(face = "bold"),
    legend.key.width = grid::unit(1.4, "cm")
  )

p_costs_legend <- get_plot_legend(p_costs_legend_source)
p_total_panel <- p_total + theme(legend.position = "none")
p_per_tour_panel <- p_per_tour + theme(legend.position = "none")

draw_costs_combined <- function() {
  grid::grid.newpage()

  layout_heights <- grid::unit.c(
    grid::unit(1, "null"),
    grid::grobHeight(p_costs_legend) + grid::unit(4, "mm")
  )

  grid::pushViewport(
    grid::viewport(
      layout = grid::grid.layout(
        nrow = 2,
        ncol = 2,
        widths = grid::unit(c(1, 1), "null"),
        heights = layout_heights
      )
    )
  )

  print(
    p_total_panel,
    vp = grid::viewport(layout.pos.row = 1, layout.pos.col = 1)
  )
  print(
    p_per_tour_panel,
    vp = grid::viewport(layout.pos.row = 1, layout.pos.col = 2)
  )

  grid::pushViewport(
    grid::viewport(layout.pos.row = 2, layout.pos.col = 1:2)
  )
  grid::grid.draw(p_costs_legend)
  grid::popViewport(2)
}

# Display combined line plot in RStudio
draw_costs_combined()


# -----------------------------------------------------------------------------
# 6. Plot for cheaper BEV share of tours and distances in 2050 (Advanced demand)
# -----------------------------------------------------------------------------

bev_gplot <- ggplot(
  bev_plot_data,
  aes(
    x = year,
    y = share,
    color = component,
    linetype = share_type,
    group = interaction(component, share_type)
  )
) +
  geom_line(linewidth = 0.9) +
  geom_point(
    aes(shape = share_type),
    size = 1.2
  ) +
  scale_x_continuous(
    name = "Energy-price year",
    breaks = c(2024, 2030, 2050)
  ) +
  scale_y_continuous(
    name = "Share [%]",
    labels = label_number(accuracy = 1),
    limits = c(0, 100),
    expand = expansion(mult = c(0.02, 0.05))
  ) +
  scale_color_brewer(palette = "Set2") +
  scale_linetype_manual(
    values = c(
      "Tours" = "solid",
      "Distance" = "dashed"
    )
  ) +
  scale_shape_manual(
    values = c(
      "Tours" = 16,
      "Distance" = 16
    )
  ) +
  labs(
    title = "Share of cost benefits for BEV under different energy-price settings",
    color = "Component",
    linetype = "Measure",
    shape = "Measure"
  ) +
  guides(
    color = guide_legend(
      position = "right",
      title = "Component"
    ),
    linetype = guide_legend(
      position = "bottom",
      title = "Measure",
      theme = theme(
        legend.key.width = grid::unit(2.0, "cm")
      )
    ),
    shape = "none"
  ) +
  theme_minimal(base_size = 12) +
  theme(
    plot.title = element_text(face = "bold", size = 16),
    axis.title.x = element_text(face = "bold"),
    axis.title.y = element_text(face = "bold"),
    panel.grid.minor = element_blank(),

    legend.title = element_text(face = "bold"),

    # Component-Legende unten möglichst horizontal
    legend.box = "horizontal",

    plot.margin = margin(10, 15, 10, 15)
  )

print(bev_gplot)
# Optional export (uncomment if desired)
# png("costs_combined_lineplot.png", width = 10, height = 4.8,
#     units = "in", res = 300, bg = "white")
# draw_costs_combined()
# dev.off()
# ggsave("total_cost_lineplot.png", p_total, width = 10, height = 6,
#        units = "in", dpi = 300, bg = "white")
# ggsave("cost_per_tour_lineplot.png", p_per_tour, width = 10, height = 6,
#        units = "in", dpi = 300, bg = "white")
# pdf("costs_combined_lineplot.pdf", width = 10, height = 4.8,
#     bg = "white")
# draw_costs_combined()
# dev.off()
# ggsave("total_cost_lineplot.pdf", p_total, width = 10, height = 6,
#        units = "in", bg = "white")
# ggsave("cost_per_tour_lineplot.pdf", p_per_tour, width = 10, height = 6,
#        units = "in", bg = "white")