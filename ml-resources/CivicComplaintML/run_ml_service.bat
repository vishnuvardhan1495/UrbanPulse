@echo off
title UrbanPulse ML Inference Service
echo ========================================================
echo Starting UrbanPulse AI Image Classification Service
echo Model: urbanpulse_8class_pothole_stage1.keras
echo Port:  5001
echo ========================================================
python "%~dp0urbanpulse_ai\app.py"
pause
