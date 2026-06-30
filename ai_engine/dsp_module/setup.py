import os
import sys
from setuptools import setup
from pybind11.setup_helpers import Pybind11Extension, build_ext

# הגדרת המודול לקומפילציה
ext_modules = [
    Pybind11Extension(
        "speaker_features",
        ["speaker_features.cpp"],
        # הגדרת תקן C++ (גרסה 14 ומעלה מספיקה ל-std::complex ו-std::norm)
        cxx_std=14,
    ),
]

setup(
    name="speaker_features",
    version="1.0.0",
    description="Self-contained Log-Mel Fbank extractor for speaker verification",
    ext_modules=ext_modules,
    cmdclass={"build_ext": build_ext},
    zip_safe=False,
    python_requires=">=3.8",
)