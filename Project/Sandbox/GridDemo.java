package Project.Sandbox;

import Project.Common.Grid;
import java.util.List;

/** Try the grid rule locally before sending any network payloads. */
public class GridDemo {
    public static void main(String[] args) {
        Grid grid = new Grid(5, 5, 12345L);
        System.out.println("Initial seeded grid:");
        System.out.println(grid);
        List<int[]> center = grid.applyModifier(2, 2, 3);
        System.out.println("Center changed " + center.size()
                + " cells; odd affected cells: " + grid.countOdd(center));
        List<int[]> corner = grid.applyModifier(0, 0, 2);
        System.out.println("Corner changed " + corner.size()
                + " cells; odd affected cells: " + grid.countOdd(corner));
        System.out.println("After both actions:");
        System.out.println(grid);
    }
}
