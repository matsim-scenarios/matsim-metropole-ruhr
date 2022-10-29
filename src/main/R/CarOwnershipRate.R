library(tidyverse)
library(dplyr)
library(patchwork)
library(scales)

### trips.csv to get car use
completeTrips <- read_csv2("/Users/gregorr/Desktop/Test/Pkw_BEsitzraten/010.output_trips.csv.gz")
##
persons <- read_csv2("/Users/gregorr/Desktop/Test/Pkw_BEsitzraten/010.output_persons.csv.gz")
## person2homeRegionAndCoord
person2region <-read_csv2("/Users/gregorr/Documents/work/respos/runs-svn/rvr-ruhrgebiet/v1.2.1/036/person2Home.csv")
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
personsNrOfCarUsers <- persons %>% count(area, usedCar)

#data tidying
personsNrOfCarUsers <- pivot_wider(personsNrOfCarUsers, names_from = usedCar, values_from = n)
#personsNrOfCarUsers <- rename(personsNrOfCarUsers, notUsedCar = "nrOfNoneCarUsers")
#personsNrOfCarUsers <- rename(personsNrOfCarUsers, usedCar= "nrOfCarUsers")

write_excel_csv2(personsNrOfCarUsers, "/Users/gregorr/Documents/work/respos/shared-svn/projects/rvr-metropole-ruhr/data/carAvailability/Pkw-Dichte_MATSim.csv")