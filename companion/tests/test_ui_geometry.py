from __future__ import annotations

import itertools
import random
import unittest

from supervisor_companion.ui_geometry import (
    fit_window,
    label_spans,
    map_cell_origin,
    map_cell_size,
    map_index,
    timeline_index,
    timeline_pick,
    timeline_runs,
    tooltip_origin,
    work_area_at,
)


class TimelineGeometryTests(unittest.TestCase):
    def test_few_stages_get_equal_segments_with_gaps(self):
        self.assertEqual(timeline_runs(["done", "current", "todo"], 300),
                         [(0, 99, "done"), (100, 199, "current"), (200, 299, "todo")])

    def test_many_stages_collapse_to_pixel_columns_by_importance(self):
        statuses = ["done"] * 5 + ["current"] + ["todo"] * 4
        self.assertEqual(timeline_runs(statuses, 5), [(0, 2, "done"), (2, 3, "current"), (3, 5, "todo")])
        self.assertEqual(timeline_runs(["partial", "done"], 1), [(0, 1, "partial")])
        self.assertEqual(timeline_runs(["todo", "partial"], 1), [(0, 1, "todo")])

    def test_empty_or_zero_width_timelines_draw_nothing(self):
        self.assertEqual(timeline_runs([], 300), [])
        self.assertEqual(timeline_runs(["done"], 0), [])

    def test_hit_testing_and_labels(self):
        self.assertEqual(timeline_index(150, 300, 3), 1)
        self.assertIsNone(timeline_index(300, 300, 3))
        self.assertIsNone(timeline_index(10, 300, 0))
        self.assertEqual(label_spans((("Structure", 2), ("Lights", 1)), 300),
                         [(0, 200, "Structure"), (200, 300, "Lights")])

    def test_pixel_columns_rank_current_then_to_do_then_partly_done_then_done(self):
        for higher, lower in itertools.combinations(("current", "todo", "partial", "done"), 2):
            for pair in ([higher, lower], [lower, higher]):
                with self.subTest(pair=pair):
                    self.assertEqual(timeline_runs(pair, 1), [(0, 1, higher)])

    def test_every_drawn_pixel_hit_tests_to_its_own_stage(self):
        statuses = ["done", "todo"] * 38
        # 1-2 px segments, the card's 3-4 px segments, and 5-6 px segments with gaps.
        for width in (100, 296, 420):
            with self.subTest(width=width):
                runs = timeline_runs(statuses, width)
                self.assertEqual(len(runs), len(statuses))
                for stage, (x0, x1, _status) in enumerate(runs):
                    self.assertEqual({timeline_index(x, width, len(statuses)) for x in range(x0, x1)}, {stage})

    def test_a_click_on_a_collapsed_column_selects_a_stage_that_column_shows(self):
        # 10 stages in 3 px: the columns show stages 0-2, 3-5 and 6-9.
        for x, stages in ((0, range(0, 3)), (0.95, range(0, 3)), (1, range(3, 6)), (2.5, range(6, 10))):
            with self.subTest(x=x):
                self.assertIn(timeline_index(x, 3, 10), stages)

    def test_zero_or_negative_widths_draw_and_hit_nothing(self):
        self.assertEqual(timeline_runs(["done"], -40), [])
        self.assertIsNone(timeline_index(0, 0, 3))
        self.assertIsNone(timeline_index(0, -40, 3))
        self.assertIsNone(timeline_index(-1, 300, 3))
        self.assertEqual(label_spans((), 300), [])
        self.assertEqual(label_spans((("Structure", 2),), 0), [])
        self.assertEqual(label_spans((("Structure", 2),), -40), [])

    def test_a_collapsed_column_picks_the_stage_that_colours_it(self):
        # 10 stages in 5 px: column 2 stands for stages 4 and 5 and is drawn blue for stage 5.
        statuses = ["done"] * 5 + ["current"] + ["todo"] * 4
        self.assertIn((2, 3, "current"), timeline_runs(statuses, 5))
        self.assertEqual(timeline_index(2, 5, len(statuses)), 4)
        self.assertEqual([timeline_pick(x, 5, statuses) for x in range(5)], [0, 2, 5, 6, 8])
        self.assertEqual(timeline_pick(2.5, 5, statuses), 5)

    def test_a_collapsed_column_picks_the_first_stage_with_its_most_important_status(self):
        for higher, lower in itertools.combinations(("current", "todo", "partial", "done"), 2):
            with self.subTest(higher=higher, lower=lower):
                self.assertEqual(timeline_pick(0, 1, [lower, higher, lower, higher]), 1)
                self.assertEqual(timeline_pick(0, 1, [higher, lower, higher]), 0)

    def test_every_pixel_picks_a_stage_it_stands_for_with_the_status_drawn_there(self):
        generator = random.Random(17)
        mixed = [generator.choice(("done", "partial", "current", "todo", "mystery")) for _ in range(700)]
        schedule = ["done"] * 300 + ["partial", "done"] * 40 + ["current"] + ["todo"] * 319
        for statuses in (mixed, schedule):
            for width in (3, 50, 296, 699):
                with self.subTest(stages=len(statuses), width=width):
                    drawn = {x: status for x0, x1, status in timeline_runs(statuses, width) for x in range(x0, x1)}
                    count = len(statuses)
                    for x in range(width):
                        stage = timeline_pick(x, width, statuses)
                        self.assertEqual(statuses[stage], drawn[x])
                        self.assertTrue(x * count // width <= stage < (x + 1) * count // width)

    def test_with_a_pixel_or_more_per_stage_picking_matches_hit_testing(self):
        statuses = ["done", "partial", "current", "todo"] * 19
        for width in (76, 100, 296, 420):
            with self.subTest(width=width):
                for x in range(-1, width + 1):
                    self.assertEqual(timeline_pick(x, width, statuses), timeline_index(x, width, len(statuses)))

    def test_picking_off_the_bar_or_on_an_empty_timeline_gives_nothing(self):
        statuses = ["done"] * 10
        for x, width in ((-1, 5), (5, 5), (0, 0), (0, -40)):
            with self.subTest(x=x, width=width):
                self.assertIsNone(timeline_pick(x, width, statuses))
        self.assertIsNone(timeline_pick(0, 300, []))


class MapGeometryTests(unittest.TestCase):
    def test_cell_size_fits_and_clamps(self):
        self.assertEqual(map_cell_size(7, 7, 300, 300), 28)
        self.assertEqual(map_cell_size(32, 32, 300, 300), 7)
        self.assertEqual(map_cell_size(1024, 1, 300, 300), 2)
        self.assertEqual(map_cell_size(0, 7, 300, 300), 0)

    def test_cell_origin_and_hit_testing(self):
        self.assertEqual(map_cell_origin(8, 7, 10), (12, 12))
        self.assertEqual(map_index(13, 13, 7, 7, 10), 8)
        self.assertIsNone(map_index(10.5, 13, 7, 7, 10))
        self.assertIsNone(map_index(200, 13, 7, 7, 10))
        self.assertIsNone(map_index(-1, 13, 7, 7, 10))

    def test_empty_maps_and_collapsed_panels_fall_back_without_raising(self):
        self.assertEqual(map_cell_size(7, 0, 300, 300), 0)
        self.assertEqual(map_cell_size(7, 7, 0, 0), 2)
        self.assertEqual(map_cell_size(7, 7, 300, -16), 2)
        self.assertEqual(map_cell_origin(3, 0, 0), (0, 0))
        self.assertIsNone(map_index(5, 5, 0, 7, 10))
        self.assertIsNone(map_index(5, 5, 7, 0, 10))
        self.assertIsNone(map_index(5, 5, 7, 7, 0))


class TooltipGeometryTests(unittest.TestCase):
    SCREEN = (0, 0, 1920, 1040)
    LEFT_MONITOR = (-1920, -200, 0, 880)

    def test_a_tooltip_with_room_sits_below_and_right_of_the_pointer(self):
        self.assertEqual(tooltip_origin(100, 100, 200, 40, self.SCREEN), (114, 118))

    def test_near_the_right_edge_the_tooltip_ends_at_the_edge(self):
        # The card's default spot is 16 px from the right edge, so its tooltips would run off the screen.
        self.assertEqual(tooltip_origin(1880, 60, 366, 23, self.SCREEN), (1554, 78))

    def test_near_the_bottom_edge_the_tooltip_goes_above_the_pointer(self):
        self.assertEqual(tooltip_origin(100, 1030, 200, 60, self.SCREEN), (114, 964))

    def test_a_tooltip_larger_than_the_work_area_starts_at_its_corner(self):
        self.assertEqual(tooltip_origin(1800, 100, 2500, 40, self.SCREEN), (0, 118))
        self.assertEqual(tooltip_origin(100, 500, 200, 1500, self.SCREEN), (114, 0))

    def test_a_second_monitor_with_a_negative_origin(self):
        areas = [self.SCREEN, self.LEFT_MONITOR]
        self.assertEqual(work_area_at(-10, 870, areas, self.SCREEN), self.LEFT_MONITOR)
        self.assertEqual(tooltip_origin(-10, 870, 366, 60, self.LEFT_MONITOR), (-366, 804))

    def test_every_tooltip_that_fits_stays_inside_and_off_the_pointer(self):
        for area in (self.SCREEN, self.LEFT_MONITOR):
            left, top, right, bottom = area
            for pointer_x, pointer_y in itertools.product(range(left, right, 97), range(top, bottom, 53)):
                for width, height in ((194, 23), (366, 53), (374, 128)):
                    with self.subTest(area=area, pointer=(pointer_x, pointer_y), size=(width, height)):
                        x, y = tooltip_origin(pointer_x, pointer_y, width, height, area)
                        self.assertTrue(left <= x and x + width <= right and top <= y and y + height <= bottom)
                        self.assertFalse(x <= pointer_x < x + width and y <= pointer_y < y + height)

    def test_the_work_area_is_the_one_holding_the_pointer_or_else_the_nearest(self):
        primary, right_monitor = (0, 0, 1920, 1040), (1920, 0, 3840, 1080)
        areas = [primary, right_monitor]
        self.assertEqual(work_area_at(2000, 500, areas, primary), right_monitor)
        self.assertEqual(work_area_at(1920, 10, areas, primary), right_monitor)
        # Over the primary monitor's taskbar, which work areas leave out.
        self.assertEqual(work_area_at(500, 1060, areas, right_monitor), primary)

    def test_without_work_areas_the_fallback_is_used(self):
        self.assertEqual(work_area_at(5, 5, [], (0, 0, 2560, 1440)), (0, 0, 2560, 1440))


class WindowPlacementTests(unittest.TestCase):
    # The Windows 10/11 frame around a window's client area (resize borders and title bar), at 100% and 150%.
    FRAME = (8, 31, 8, 8)
    FRAME_150 = (12, 46, 12, 12)
    SCREEN = (0, 0, 2560, 1392)

    def test_a_window_that_fits_keeps_its_size_and_place(self):
        self.assertEqual(fit_window((960, 720), (100, 100), self.SCREEN, self.FRAME), (960, 720, 100, 100))

    def test_without_a_position_the_window_is_centred_frame_and_all(self):
        # 976 x 759 with its frame: (2560 - 976) // 2 = 792 and (1392 - 759) // 2 = 316.
        self.assertEqual(fit_window((960, 720), None, self.SCREEN, self.FRAME), (960, 720, 792, 316))

    def test_the_default_full_window_at_150_percent_fits_a_1080p_work_area(self):
        # 960 x 720 at 150% is 1440 x 1080, taller than a 1920 x 1080 screen's work area before the frame.
        width, height, x, y = fit_window((1440, 1080), None, (0, 0, 1920, 1032), self.FRAME_150)
        self.assertEqual((width, height, x, y), (1440, 974, 228, 0))
        self.assertLessEqual(x + 12 + width + 12, 1920)
        self.assertLessEqual(y + 46 + height + 12, 1032)

    def test_a_window_partly_off_the_work_area_moves_back_inside(self):
        self.assertEqual(fit_window((960, 720), (2000, 1000), self.SCREEN, self.FRAME), (960, 720, 1584, 633))
        self.assertEqual(fit_window((960, 720), (-50, -20), self.SCREEN, self.FRAME), (960, 720, 0, 0))

    def test_a_window_on_a_monitor_with_a_negative_origin_stays_on_it(self):
        left_monitor = (-1920, 0, 0, 1040)
        self.assertEqual(fit_window((1440, 1080), (-1800, 100), left_monitor, self.FRAME_150),
                         (1440, 982, -1800, 0))

    def test_the_minimum_size_applies_unless_the_work_area_is_smaller(self):
        self.assertEqual(fit_window((300, 250), (100, 100), self.SCREEN, self.FRAME, minimum=(720, 560)),
                         (720, 560, 100, 100))
        self.assertEqual(fit_window((960, 720), None, (0, 0, 640, 480), self.FRAME, minimum=(720, 560)),
                         (624, 441, 0, 0))

    def test_a_work_area_smaller_than_the_frame_keeps_a_one_pixel_window_at_its_corner(self):
        self.assertEqual(fit_window((960, 720), None, (0, 0, 10, 10), self.FRAME), (1, 1, 0, 0))

    def test_every_fitted_window_lies_inside_its_work_area_frame_and_all(self):
        for area in (self.SCREEN, (-1920, -200, 0, 880), (0, 0, 1920, 1032)):
            left, top, right, bottom = area
            for size in ((960, 720), (1440, 1080), (3000, 2000), (200, 200)):
                for position in (None, (left - 500, top - 500), (right - 10, bottom - 10), (left + 40, top + 40)):
                    with self.subTest(area=area, size=size, position=position):
                        width, height, x, y = fit_window(size, position, area, self.FRAME_150, minimum=(1080, 840))
                        self.assertTrue(left <= x and x + width + 24 <= right and top <= y
                                        and y + height + 58 <= bottom)
                        self.assertTrue(width >= 1080 and height >= 840)


if __name__ == "__main__":
    unittest.main()
