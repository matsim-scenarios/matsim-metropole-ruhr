library(tidyverse)
library(sf)
library(stringr)
library(tidyr)
#library(matsim)
library(hms)

#==================================================
# Read MATSim trips
#==================================================

trips <- read_csv2(
  "/Users/gregorr/Volumes/math-cluster/matsim-metropole-ruhr/experiments-based-on-v2024.2/bike/same-infra-speed-factor/runs/003/003.output_trips.csv.gz"
)

#==================================================
# Filter bike trips
#==================================================

bikeTrips <- trips %>%
  filter(main_mode == "bike")

#==================================================
# Calculate travel time and speed
#==================================================

bikeTrips <- bikeTrips %>%
  mutate(
    travel_time_s = as.numeric(trav_time),
    speed_kmh = traveled_distance / travel_time_s * 3.6
  )


##we have an issue with bike speeds of zero probably sth with the activty scoring.
bikeTrips <- filter(bikeTrips, speed_kmh !=0)



#==================================================
# Summary statistics
#==================================================

cat("Number of bike trips:", nrow(bikeTrips), "\n")
cat("Mean speed:", round(mean(bikeTrips$speed_kmh, na.rm = TRUE), 2), "km/h\n")
cat("Median speed:", round(median(bikeTrips$speed_kmh, na.rm = TRUE), 2), "km/h\n")
cat("Standard deviation:", round(sd(bikeTrips$speed_kmh, na.rm = TRUE), 2), "km/h\n")

summary(bikeTrips$speed_kmh)

#==================================================
# Plot speed distribution
#==================================================

ggplot(bikeTrips, aes(speed_kmh)) +
  geom_histogram(binwidth = 1, boundary = 0) +
  labs(
    title = "Distribution of bicycle speeds",
    x = "Speed (km/h)",
    y = "Number of trips"
  ) +
  theme_minimal()