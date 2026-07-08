library(tidyverse)
library(sf)
library(stringr)
library(tidyr)

#==================================================
# Configuration
#==================================================

scale_factor <- 100   # 10 for 10% sample, 100 for 1% sample

#==================================================
# Read data
#==================================================

countsRVR <- st_read("/Users/gregorr/Downloads/20251002_Übersicht_Zählstellen_Rad_LinkIDs.gpkg")

matsimLinkStats <- read_csv2(
  "/Users/gregorr/Documents/work/respos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.2/output/metropole-ruhr-v2024.2-1pct/gr_analysis/linkPaxVolumesPerNetworkModePerHour.csv.gz"
)

#==================================================
# Keep only bike traffic
#==================================================

bikeMATSimStatsHourly <- matsimLinkStats %>%
  filter(networkMode == "bike")

#==================================================
# Aggregate hourly volumes to daily volumes
#==================================================

bikeMATSimStatsDaily <- bikeMATSimStatsHourly %>%
  group_by(link) %>%
  summarise(
    vehicles = sum(vehicles, na.rm = TRUE) * scale_factor,
    passengersInclDriver = sum(passengersInclDriver, na.rm = TRUE) * scale_factor,
    .groups = "drop"
  )

#==================================================
# Prepare RVR count data
#==================================================

counts_long <- st_drop_geometry(countsRVR)

counts_long$id <- seq_len(nrow(counts_long))

# Split multiple MATSim links into individual rows
counts_long <- counts_long %>%
  separate_rows(LinksMATSim, sep = ",") %>%
  mutate(LinksMATSim = str_trim(LinksMATSim))

#==================================================
# Match MATSim volumes to each MATSim link
#==================================================

matches <- counts_long %>%
  left_join(
    bikeMATSimStatsDaily,
    by = c("LinksMATSim" = "link")
  )

matches$vehicles[is.na(matches$vehicles)] <- 0
matches$passengersInclDriver[is.na(matches$passengersInclDriver)] <- 0

#==================================================
# Inspect matching results
#==================================================

View(matches)

#==================================================
# Aggregate back to count stations
#==================================================

volumes <- matches %>%
  group_by(id) %>%
  summarise(
    vol_bike_sum = sum(vehicles),
    passengers_sum = sum(passengersInclDriver),
    .groups = "drop"
  )

#==================================================
# Join back to original spatial data
#==================================================

countsRVR$id <- seq_len(nrow(countsRVR))

countsRVR_matched <- countsRVR %>%
  left_join(volumes, by = "id")

#==================================================
# Optional checks
#==================================================

# Show unmatched MATSim links
matches %>%
  filter(vehicles == 0)

# Inspect final result
View(countsRVR_matched)

max_val <- max(
  countsRVR_matched$X24hcountDOquer,
  countsRVR_matched$vol_bike_sum,
  na.rm = TRUE
)

p <- ggplot(
  countsRVR_matched,
  aes(x = X24hcountDOquer, y = vol_bike_sum)
) +
  geom_point(size = 3, alpha = 0.7) +
  geom_abline(intercept = 0, slope = 1,
              colour = "red", linewidth = 1.2) +
  coord_equal(xlim = c(0, max_val), ylim = c(0, max_val)) +
  labs(
    x = "Observed 24h bicycle count",
    y = "MATSim 24h bicycle volume",
    title = "Observed vs. simulated bicycle volumes"
  ) +
  theme_bw(base_size = 18)

p

