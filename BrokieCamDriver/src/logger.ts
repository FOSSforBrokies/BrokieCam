type Level = "DEBUG" | "INFO" | "WARN" | "ERROR";

function timestamp(){
    return new Date().toISOString();
}

export function log(level: Level, component: string, message: string){
    console.log(
        `${timestamp()} [${level}] [${component}] ${message}`
    )
}