#!/usr/bin/env python
# -*- coding: utf-8 -*-

import numpy as np

from matsim.scenariogen.data import TripMode, run_create_ref_data


def person_filter(df):
    """ Default person filter for reference data. """
    return df[df.reporting_day <= 5]


def trip_filter(df):
    # Motorcycles are counted as cars
    df.loc[df.main_mode == TripMode.MOTORCYCLE, "main_mode"] = TripMode.CAR

    # Other modes are ignored in the total share
    return df[df.main_mode != "other"]


if __name__ == "__main__":
    result = run_create_ref_data.create("../../../../shared-svn/projects/rvr-metropole-ruhr/data/MID",
                                        person_filter, trip_filter,
                                        dist_groups=[0, 1000, 2000, 5000, 10000, 20000, 50000, np.inf],
                                        invalid_trip_handling=run_create_ref_data.InvalidHandling.REMOVE_PERSONS)

    print(result.share)
