package com.hermes.control;

interface IShellService {
    String exec(String command, int timeoutSeconds);
}
