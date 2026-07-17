#!/bin/bash
set -e

cd /home/rudy/RacingGame

echo "Creating local.properties..."
echo "sdk.dir=/home/rudy/Android/Sdk" > ControllerApp/local.properties
echo "sdk.dir=/home/rudy/Android/Sdk" > TVApp/local.properties

echo "Downloading Gradle..."
wget -q https://services.gradle.org/distributions/gradle-8.4-bin.zip
unzip -q gradle-8.4-bin.zip

echo "Building ControllerApp..."
cd ControllerApp
../gradle-8.4/bin/gradle wrapper
./gradlew assembleDebug
cd ..

echo "Building TVApp..."
cd TVApp
../gradle-8.4/bin/gradle wrapper
./gradlew assembleDebug
cd ..

echo "Cleaning up..."
rm -rf gradle-8.4-bin.zip gradle-8.4

echo "APK Builds complete!"
