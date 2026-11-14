# -*- coding: utf-8 -*-
"""محتوای دفترچه: بخش‌ها به ترتیب."""

import content_p1
import content_p2
import content_p3


def render(pdf) -> None:
    content_p1.render(pdf)
    content_p2.render(pdf)
    content_p3.render(pdf)
