package io.github.ouse.deskkeys;

interface IWindowControl {
    void destroy() = 16777114;
    int capabilities() = 1;
    String performWindow(int action, String targetPackage, long requestedAt) = 2;
}
