#!/usr/bin/env python
# -*- coding: utf-8 -*-

import os

import pandas as pd
import geopandas as gpd
import numpy as np

try:
    # Use the matsim package if available
    from matsim import calibration
except:
    # Alternatively, import calibration.py from same directory
    import calibration

# %%

modes = ["walk", "car", "ride", "pt", "bike"]
fixed_mode = "walk"

# Initial ASCs
initial = {
    "bike": 0,
    "pt": 0,
    "car": 1,
    "ride": -2.3
}

# Modal split target
target = {
    "walk": 0.17,
    "bike": 0.19,
    "pt": 0.08,
    "car": 0.46,
    "ride": 0.10
}

region = gpd.read_file("../input/Stadtgebiet_Hamm.zip").set_crs("EPSG:25832")
homes = pd.read_csv("../input/hamm-v1.4-homes.csv", dtype={"person": "str"})


def f(persons):
    persons = pd.merge(persons, homes, how="inner", left_on="person", right_on="person")
    persons = gpd.GeoDataFrame(persons, geometry=gpd.points_from_xy(persons.home_x, persons.home_y))

    df = gpd.sjoin(persons.set_crs("EPSG:25832"), region, how="inner", op="intersects")

    print("Filtered %s persons" % len(df))

    return df


def adjust_trips(df):
    df = df[df.main_mode != "freight"]

    # Assign all intermodal pt trips to pt as main mode
    df.loc[df.main_mode.str.startswith("pt_"), "main_mode"] = "pt"

    return df


study, obj = calibration.create_mode_share_study("calib", "matsim-metropole-ruhr-1.4.jar",
                                                 "../input/hamm-v1.4-25pct.config.xml",
                                                 modes, target,
                                                 initial_asc=initial,
                                                 args="--25pct",
                                                 jvm_args="-Xmx60G -Xmx60G -XX:+AlwaysPreTouch",
                                                 person_filter=f, map_trips=adjust_trips, chain_runs=True)

# %%

study.optimize(obj, 5)
