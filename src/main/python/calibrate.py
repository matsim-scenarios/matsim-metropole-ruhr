#!/usr/bin/env python
# -*- coding: utf-8 -*-

import os

import pandas as pd
import geopandas as gpd
import numpy as np

try:
    from matsim import calibration
except:
    import calibration

#%%

if os.path.exists("mid.csv"):
    srv = pd.read_csv("mid.csv")
    sim = pd.read_csv("sim.csv")

    _, adj = calibration.calc_adjusted_mode_share(sim, srv)

    print(srv.groupby("mode").sum())

    print("Adjusted")
    print(adj.groupby("mode").sum())

    adj.to_csv("mid_adj.csv", index=False)

#%%

modes = ["walk", "car", "ride", "pt", "bike"]
fixed_mode = "walk"
initial = {
    "bike": -2.3,
    "pt": 0,
    "car": 0,
    "ride": -4.12
}

# Use adjusted modal split for our distance distribution
target = {
    "walk":  0.212802,
    "bike":  0.095676,
    "pt":    0.118528,
    "car":   0.454354,
    "ride":  0.118640
}

region = gpd.read_file("../scenarios/metropole-ruhr-v1.0/shape/dilutionArea.shp").set_crs("EPSG:25832")

def f(persons):    
    df = gpd.sjoin(persons.set_crs("EPSG:25832"), region, how="inner", op="intersects")
    return df

def filter_freight(df):
    return df[df.main_mode != "freight"]

#print(calibration.calc_mode_share("runs/015", map_trips=filter_freight))

study, obj = calibration.create_mode_share_study("calib", "matsim-metropole-ruhr-1.0-SNAPSHOT.jar",
                                        "../scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0-10pct.config.xml",
                                        modes, target, 
                                        initial_asc=initial,
                                        args="--10pct",
                                        jvm_args="-Xmx68G -Xmx68G -XX:+AlwaysPreTouch",
                                        person_filter=f, map_trips=filter_freight)


#%%

study.optimize(obj, 10)
