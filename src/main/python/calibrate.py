#!/usr/bin/env python
# -*- coding: utf-8 -*-

import geopandas as gpd

from matsim.calibration import create_calibration, ASCCalibrator, utils

# %%

modes = ["walk", "car", "ride", "pt", "bike"]
fixed_mode = "walk"

# Initial ASCs
initial = {
    "bike": -0.15,
    "pt": 0,
    "car": 0.8,
    "ride": -2.3
}

# Modal split target
target = {
    "walk": 0.232118,
    "bike": 0.098503,
    "pt": 0.116146,
    "car": 0.424297,
    "ride": 0.128936
}

region = gpd.read_file("../scenarios/metropole-ruhr-v1.0/shape/dilutionArea.shp").set_crs("EPSG:25832")


def f(persons):
    persons = gpd.GeoDataFrame(persons, geometry=gpd.points_from_xy(persons.home_x, persons.home_y))

    df = gpd.sjoin(persons.set_crs("EPSG:25832"), region, how="inner", op="intersects")
    return df


def adjust_trips(df):
    df = df[df.main_mode != "freight"]

    # Assign all intermodal pt trips to pt as main mode
    df.loc[df.main_mode.str.startswith("pt_"), "main_mode"] = "pt"

    return df


study, obj = create_calibration(
    "calib",
    ASCCalibrator(modes, initial, target, lr=utils.linear_scheduler(start=0.3, interval=8)),
    "matsim-metropole-ruhr-1.4.1-136d8aa.jar",
    "../scenarios/metropole-ruhr-v2.0/input/metropole-ruhr-v2.0-3pct.config.xml",
    args="--3pct",
    jvm_args="-Xmx68G -Xmx68G -XX:+AlwaysPreTouch",
    transform_persons=f, transform_trips=adjust_trips,
    chain_runs=utils.default_chain_scheduler, debug=False
)

# %%

study.optimize(obj, 3)
