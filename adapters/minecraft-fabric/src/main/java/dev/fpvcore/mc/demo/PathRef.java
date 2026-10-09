package dev.fpvcore.mc.demo;

import org.joml.Vector3d;

/** A time-parameterised reference trajectory (position, velocity, acceleration). */
public interface PathRef { Vector3d pos(double t); Vector3d vel(double t); Vector3d acc(double t); }
