library(tidyverse)
library(dplyr)
library(patchwork)
library(scales)
library(sf)

### trips.csv to get car use
completeTrips <- read_csv2("/Users/gregorr/Desktop/Test/Pkw_BEsitzraten/010.output_trips.csv.gz", col_types = cols(person = col_character()))
##
persons <- read_csv2("/Users/gregorr/Desktop/Test/Pkw_BEsitzraten/010.output_persons.csv.gz", col_types = cols(person = col_character()))
## person2homeRegionAndCoord
person2region <- read_csv("/Users/gregorr/Documents/work/respos/shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-25pct.plans-homes.csv",
                          col_types = cols(person = col_character(), home_x = col_double(), home_y = col_double()
                          ))

shape <- st_read("/Users/gregorr/Documents/work/respos/shared-svn/projects/rvr-metropole-ruhr/data/shapeFiles/dvg2krs_ruhrgebiet-rvr/dvg2krs_ruhrgebiet-rvr.shp")
person2region <- person2region %>%
  st_as_sf(coords = c("home_x", "home_y"), crs = 25832) %>%
  st_join(shape)
person2region <- select(person2region, person, geometry, GN)
person2region <- person2region %>% replace(is.na(.), "other")

## joining person and persons2region
persons <- left_join(persons, person2region, by= "person")

#only selecting interesting rows
completeTrips <- select(completeTrips, person, main_mode) 
carTrips <- filter(completeTrips, main_mode=='car')

#counting the amount of car trips per agent
carTrips <-  carTrips %>%
  group_by(person)  %>%
  count(main_mode)
carTrips <- select(carTrips, person, n)
carTrips <- rename(carTrips, "nrOfCarTrips" = n)
persons <- left_join(persons, carTrips, by="person")
persons <- persons %>% replace(is.na(.), 0)

#create variable whether car was used or not used by the agent
persons<-persons %>% 
  mutate(usedCar = ifelse(nrOfCarTrips >= 1, "usedCar", "notUsedCar")) 

#count how often each case happens in the different areas
personsNrOfCarUsers <- persons %>% count(GN, usedCar)

#data tidying
personsNrOfCarUsers <- pivot_wider(personsNrOfCarUsers, names_from = usedCar, values_from = n)
#personsNrOfCarUsers <- rename(personsNrOfCarUsers, notUsedCar = "nrOfNoneCarUsers")
#personsNrOfCarUsers <- rename(personsNrOfCarUsers, usedCar= "nrOfCarUsers")

write_excel_csv2(personsNrOfCarUsers, "/Users/gregorr/Documents/work/respos/shared-svn/projects/rvr-metropole-ruhr/data/carAvailability/Pkw-Dichte_MATSim.csv")