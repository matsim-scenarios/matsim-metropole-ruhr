library(tidyverse)
library(dplyr)
library(patchwork)
library(networkD3)
library(sf) #=> geography
library(matsim)
library(stringr)
library("xlsx")

##the trips.csv must be located in the output directory

prepareSimwrapperDashboardFromFolder("outputDirectoty", "simWrapperFiles")