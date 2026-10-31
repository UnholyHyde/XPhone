package ru.xphone.booster;

// Служба, которая работает через Shizuku с правами adb (shell) и выполняет команды.
interface IShellService {
    // Код 16777114 требует Shizuku: так он закрывает службу.
    void destroy() = 16777114;

    String exec(String command) = 1;
}
