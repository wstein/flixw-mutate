enum Color {
    case Red
    case Green
}

instance ToString[Color] {
    pub def toString(c: Color): String = match c {         // H
        case Color.Red => "red"                            // H
        case Color.Green => "green"                        // M
    }
}
