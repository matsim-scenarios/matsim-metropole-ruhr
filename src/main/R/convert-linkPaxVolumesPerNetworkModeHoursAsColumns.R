library(tidyverse)

# set working directory to where the files are located
setwd("~/Desktop")

# specify input file
data <- read_delim("021.linkPaxVolumesPerNetworkModePerHour.csv.gz",
           delim=";")

# write rows to additional columns using the 'pivot_wider' function
vehicles_hours_as_columns <- data %>%
  mutate(passengersInclDriver=NULL) %>% 
  pivot_wider(names_from = hour, values_from = vehicles,
              names_prefix = "hour_")

# only keep car mode
vehicles_hours_as_columns_car <- vehicles_hours_as_columns %>%
  filter(networkMode == "car") %>%
  mutate(networkMode=NULL) %>%
  mutate(sumDay = rowSums(.[2:30]))

# only keep bike mode
vehicles_hours_as_columns_bike <- vehicles_hours_as_columns %>%
  filter(networkMode == "bike") %>%
  mutate(networkMode=NULL) %>%
  mutate(sumDay = rowSums(.[2:30]))

# write car data
write_delim(vehicles_hours_as_columns_car,
            "021.linkPaxVolumesPerNetworkModeHoursAsColumns_car_vehicles.csv",
            delim=";")

# write bike data
write_delim(vehicles_hours_as_columns_bike,
            "021.linkPaxVolumesPerNetworkModeHoursAsColumns_bike_vehicles.csv",
            delim=";")
