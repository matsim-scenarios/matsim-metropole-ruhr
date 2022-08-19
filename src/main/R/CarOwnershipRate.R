library(tidyverse)
library(dplyr)
library(patchwork)
library(scales)

### trips.csv to get car use
completeTrips <- read_csv2("/Users/gregorr/Documents/work/respos/runs-svn/rvr-ruhrgebiet/v1.2.1/036/036.output_trips.csv.gz")
## persons.csv to get SNZ attributes
persons <- read_csv2("/Users/gregorr/Documents/work/respos/runs-svn/rvr-ruhrgebiet/v1.2.1/036/036.output_persons.csv.gz")
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

#create variable wether car was used or not used by the agent
persons<-persons %>% 
  mutate(usedCar = ifelse(nrOfCarTrips >= 1, "usedCar", "notUsedCar")) 

##snz Data
snzCarAvailability <- persons %>% count(area, sim_carAvailability)
## calculate relative values
snzCarAvailability <- snzCarAvailability %>% group_by(area) %>% mutate(Percentage=round((n/sum(n)),2)) %>% ungroup()

#grouped relative plot
ggplot(snzCarAvailability, aes(fill=sim_carAvailability, y=area, x=Percentage)) + 
  geom_bar(position="stack", stat="identity") +
  xlab("Prozent") +
  ylab("Landkreis") +
  ggtitle("snzCarAvailability Attribut")+
  geom_text(aes(label = percent(Percentage)), position = position_stack(vjust = 0.5)) +
  scale_x_continuous(labels = scales::percent) +
  scale_fill_discrete(labels = c("Pkw-Besitzer", "nicht Pkw-Besitzer"), name ="Besitzrate") +
  theme_minimal()

#count how often each case happens in the different areas
personsNrOfCarUsers <- persons %>% count(area, usedCar)

#Grouped absolute plot
ggplot(personsNrOfCarUsers, aes(fill=usedCar, y=area, x=n)) + 
  geom_bar(position="dodge", stat="identity") +
  xlab("Anzahl") +
  ylab("Landkreis")+
  theme_minimal()

## calculate relative values
personsNrOfCarUsers <- personsNrOfCarUsers %>% group_by(area) %>% mutate(Percentage=round((n/sum(n)),2)) %>% ungroup()

##calculate numer of cars per 1000 inhabitants
personsNrOfCarUsers <- personsNrOfCarUsers %>% group_by(area) %>% mutate(nrPer1000Inhabitants=round(Percentage*1000,2)) %>% ungroup()

#grouped relative plot
ggplot(personsNrOfCarUsers, aes(fill=usedCar, y=area, x=Percentage)) + 
  geom_bar(position="stack", stat="identity") +
  xlab("Prozent") +
  ylab("Landkreis") +
  ggtitle("Resultate des Modells")+
  geom_text(aes(label = percent(Percentage)), position = position_stack(vjust = 0.5)) +
  scale_x_continuous(labels = scales::percent) +
  scale_fill_discrete(labels = c("Pkw-Besitzer", "nicht Pkw-Besitzer"), name ="Besitzrate") +
  theme_minimal()

#grouped absolute plot
ggplot(personsNrOfCarUsers, aes(fill=usedCar, y=area, x=nrPer1000Inhabitants)) + 
  geom_bar(position="stack", stat="identity") +
  xlab("Anzahl") +
  ylab("Landkreis") +
  ggtitle("Resultate des Modells")+
  geom_text(aes(label = nrPer1000Inhabitants), position = position_stack(vjust = 0.5)) +
  scale_x_continuous() +
  scale_fill_discrete(labels = c("Pkw-Besitzer", "nicht Pkw-Besitzer"), name ="Anzahl je 1000 Einwohner") +
  theme_minimal()



