library(tidyverse)

# set working directory to where the files are located
setwd("C:/Users/Janekdererste/Desktop/metropole-ruhr-036")
print("start reading file")
# specify input file
data <- read_delim("036.linkPaxVolumesPerNetworkModePerHour.csv.gz",
           delim=";")
head(data)

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

print("write data")
# write car data
write_delim(vehicles_hours_as_columns_car,
            "036.linkPaxVolumesPerNetworkModeHoursAsColumns_car_vehicles.csv",
            delim=";")

# write bike data
write_delim(vehicles_hours_as_columns_bike,
            "036.linkPaxVolumesPerNetworkModeHoursAsColumns_bike_vehicles.csv",
            delim=";")
print("done")